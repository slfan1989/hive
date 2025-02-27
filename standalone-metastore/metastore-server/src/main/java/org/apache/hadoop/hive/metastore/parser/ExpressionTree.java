/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.metastore.parser;

import java.sql.Timestamp;
import java.sql.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.ColumnType;
import org.apache.hadoop.hive.metastore.DatabaseProduct;
import org.apache.hadoop.hive.metastore.Warehouse;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.hive_metastoreConstants;
import org.apache.hadoop.hive.metastore.conf.MetastoreConf;
import org.apache.hadoop.hive.metastore.utils.MetaStoreUtils;

import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

/**
 * The Class representing the filter as a  binary tree. The tree has TreeNode's
 * at intermediate level and the leaf level nodes are of type LeafNode.
 */
public class ExpressionTree {
  /** The empty tree that can be returned for an empty filter. */
  public static final ExpressionTree EMPTY_TREE = new ExpressionTree();

  /** The logical operations supported. */
  public enum LogicalOperator {
    AND,
    OR
  }

  /** The operators supported. */
  public enum Operator {
    EQUALS  ("=", "==", "="),
    GREATERTHAN  (">"),
    LESSTHAN  ("<"),
    LESSTHANOREQUALTO ("<="),
    GREATERTHANOREQUALTO (">="),
    LIKE ("LIKE", "matches", "like"),
    NOTEQUALS2 ("!=", "!=", "<>"),
    NOTEQUALS ("<>", "!=", "<>");

    private final String op;
    private final String jdoOp;
    private final String sqlOp;

    // private constructor
    Operator(String op){
      this.op = op;
      this.jdoOp = op;
      this.sqlOp = op;
    }

    Operator(String op, String jdoOp, String sqlOp){
      this.op = op;
      this.jdoOp = jdoOp;
      this.sqlOp = sqlOp;
    }

    public String getOp() {
      return op;
    }

    public String getJdoOp() {
      return jdoOp;
    }

    public String getSqlOp() {
      return sqlOp;
    }

    public static Operator fromString(String inputOperator) {
      for(Operator op : Operator.values()) {
        if(op.getOp().equals(inputOperator)){
          return op;
        }
      }

      throw new Error("Invalid value " + inputOperator +
          " for " + Operator.class.getSimpleName());
    }

    public static boolean isEqualOperator(Operator op) {
      return op == EQUALS;
    }

    public static boolean isNotEqualOperator(Operator op) {
      return op == NOTEQUALS || op == NOTEQUALS2;
    }

    @Override
    public String toString() {
      return op;
    }

  }

  /**
   * Depth first traversal of ExpressionTree.
   * The users should override the subset of methods to do their stuff.
   */
  public static class TreeVisitor {
    private void visit(TreeNode node) throws MetaException {
      if (shouldStop()) return;
      assert node != null && node.getLhs() != null && node.getRhs() != null;
      beginTreeNode(node);
      node.lhs.accept(this);
      midTreeNode(node);
      node.rhs.accept(this);
      endTreeNode(node);
    }

    protected void beginTreeNode(TreeNode node) throws MetaException {}
    protected void midTreeNode(TreeNode node) throws MetaException {}
    protected void endTreeNode(TreeNode node) throws MetaException {}
    protected void visit(LeafNode node) throws MetaException {}
    protected boolean shouldStop() {
      return false;
    }
  }

  /**
   * Helper class that wraps the stringbuilder used to build the filter over the tree,
   * as well as error propagation in two modes - expect errors, i.e. filter might as well
   * be unbuildable and that's not a failure condition; or don't expect errors, i.e. filter
   * must be buildable.
   */
  public static class FilterBuilder {
    private final StringBuilder result = new StringBuilder();
    private String errorMessage = null;
    private boolean expectNoErrors = false;

    public FilterBuilder(boolean expectNoErrors) {
      this.expectNoErrors = expectNoErrors;
    }

    public String getFilter() throws MetaException {
      assert errorMessage == null;
      if (errorMessage != null) {
        throw new MetaException("Trying to get result after error: " + errorMessage);
      }
      return result.toString();
    }

    @Override
    public String toString() {
      try {
        return getFilter();
      } catch (MetaException ex) {
        throw new RuntimeException(ex);
      }
    }

    public String getErrorMessage() {
      return errorMessage;
    }

    public boolean hasError() {
      return errorMessage != null;
    }

    public FilterBuilder append(String filterPart) {
      this.result.append(filterPart);
      return this;
    }

    public void setError(String errorMessage) throws MetaException {
      this.errorMessage = errorMessage;
      if (expectNoErrors) {
        throw new MetaException(errorMessage);
      }
    }
  }

  /**
   * The Class representing a Node in the ExpressionTree.
   */
  public static class TreeNode {
    private TreeNode lhs;
    private LogicalOperator andOr;
    private TreeNode rhs;

    public TreeNode() {
    }

    public TreeNode(TreeNode lhs, LogicalOperator andOr, TreeNode rhs) {
      this.lhs = lhs;
      this.andOr = andOr;
      this.rhs = rhs;
    }

    public TreeNode getLhs() {
      return lhs;
    }

    public LogicalOperator getAndOr() {
      return andOr;
    }

    public TreeNode getRhs() {
      return rhs;
    }

    /** Double dispatch for TreeVisitor. */
    protected void accept(TreeVisitor visitor) throws MetaException {
      visitor.visit(this);
    }

    @Override
    public String toString() {
      return "TreeNode{" +
              "lhs=" + lhs +
              ", andOr='" + andOr + '\'' +
              ", rhs=" + rhs +
              '}';
    }
  }

  /**
   * The Class representing the leaf level nodes in the ExpressionTree.
   */
  public static class LeafNode extends TreeNode {
    public String keyName;
    public Operator operator;
    /**
     * Constant expression side of the operator. Can currently be a String or a Long.
     */
    public Object value;
    public boolean isReverseOrder = false;

    @Override
    protected void accept(TreeVisitor visitor) throws MetaException {
      visitor.visit(this);
    }

    @Override
    public String toString() {
      return "LeafNode{" +
          "keyName='" + keyName + '\'' +
          ", operator='" + operator + '\'' +
          ", value=" + value +
          (isReverseOrder ? ", isReverseOrder=true" : "") +
          '}';
    }

    /**
     * Get partition column index in the table partition column list that
     * corresponds to the key that is being filtered on by this tree node.
     * @param partitionKeys list of partition keys.
     * @param filterBuilder filter builder used to report error, if any.
     * @return The index.
     */
    public static int getPartColIndexForFilter(String partitionKeyName,
        List<FieldSchema> partitionKeys, FilterBuilder filterBuilder) throws MetaException {
      int partitionColumnIndex = Iterables.indexOf(partitionKeys, key -> partitionKeyName.equalsIgnoreCase(key.getName()));
      if( partitionColumnIndex < 0) {
        filterBuilder.setError("Specified key <" + partitionKeyName +
            "> is not a partitioning key for the table");
        return -1;
      }
      return partitionColumnIndex;
    }
  }

  /**
   * Generate the JDOQL filter for the given expression tree
   */
  public static class JDOFilterGenerator extends TreeVisitor {

    private static final String PARAM_PREFIX = "hive_filter_param_";

    private Configuration conf;
    private List<FieldSchema> partitionKeys;
    // the filter builder to append to.
    private FilterBuilder filterBuilder;
    // the input map which is updated with the the parameterized values.
    // Keys are the parameter names and values are the parameter values
    private Map<String, Object> params;
    private boolean onParsing = false;
    private String keyName;
    private Object value;
    private Operator operator;
    private boolean isReverseOrder;

    public JDOFilterGenerator(Configuration conf, List<FieldSchema> partitionKeys,
        FilterBuilder filterBuilder, Map<String, Object> params) {
      this.conf = conf;
      this.partitionKeys = partitionKeys;
      this.filterBuilder = filterBuilder;
      this.params = params;
    }

    private void beforeParsing() throws MetaException {
      if (!onParsing && !filterBuilder.getFilter().isEmpty()) {
        filterBuilder.append(" && ");
      }
      onParsing = true;
    }

    @Override
    protected void beginTreeNode(TreeNode node) throws MetaException {
      beforeParsing();
      filterBuilder.append("( ");
    }

    @Override
    protected void midTreeNode(TreeNode node) throws MetaException {
      filterBuilder.append((node.getAndOr() == LogicalOperator.AND) ? " && " : " || ");
    }

    @Override
    protected void endTreeNode(TreeNode node) throws MetaException {
      filterBuilder.append(") ");
    }

    @Override
    protected void visit(LeafNode node) throws MetaException {
      beforeParsing();
      keyName = node.keyName;
      operator = node.operator;
      value = node.value;
      isReverseOrder = node.isReverseOrder;
      if (node.keyName.startsWith(hive_metastoreConstants.HIVE_FILTER_FIELD_PARAMS)
          && DatabaseProduct.isDerbyOracle() && node.operator == Operator.EQUALS) {
        // Rewrite the EQUALS operator to LIKE
        operator = Operator.LIKE;
      }
      if (partitionKeys != null) {
        generateJDOFilterOverPartitions(conf, params, filterBuilder, partitionKeys);
      } else {
        generateJDOFilterOverTables(params, filterBuilder);
      }
    }

    @Override
    protected boolean shouldStop() {
      return filterBuilder.hasError();
    }

    //can only support "=" and "!=" for now, because our JDO lib is buggy when
    // using objects from map.get()
    private static final Set<Operator> TABLE_FILTER_OPS = Sets.newHashSet(
        Operator.EQUALS, Operator.NOTEQUALS, Operator.NOTEQUALS2, Operator.LIKE);

    private void generateJDOFilterOverTables(Map<String, Object> params,
        FilterBuilder filterBuilder) throws MetaException {
      if (keyName.equals(hive_metastoreConstants.HIVE_FILTER_FIELD_TABLE_NAME)) {
        keyName = "this.tableName";
      } else if (keyName.equals(hive_metastoreConstants.HIVE_FILTER_FIELD_TABLE_TYPE)) {
        keyName = "this.tableType";
      } else if (keyName.equals(hive_metastoreConstants.HIVE_FILTER_FIELD_OWNER)) {
        keyName = "this.owner";
      } else if (keyName.equals(hive_metastoreConstants.HIVE_FILTER_FIELD_LAST_ACCESS)) {
        //lastAccessTime expects an integer, so we cannot use the "like operator"
        if (operator == Operator.LIKE) {
          filterBuilder.setError("Like is not supported for HIVE_FILTER_FIELD_LAST_ACCESS");
          return;
        }
        keyName = "this.lastAccessTime";
      } else if (keyName.startsWith(hive_metastoreConstants.HIVE_FILTER_FIELD_PARAMS)) {
        if (!TABLE_FILTER_OPS.contains(operator)) {
          filterBuilder.setError("Only " + TABLE_FILTER_OPS + " are supported " +
            "operators for HIVE_FILTER_FIELD_PARAMS");
          return;
        }
        String paramKeyName = keyName.substring(hive_metastoreConstants.HIVE_FILTER_FIELD_PARAMS.length());
        keyName = "this.parameters.get(\"" + paramKeyName + "\")";
        //value is persisted as a string in the db, so make sure it's a string here
        // in case we get a long.
        value = value.toString();
        // dot in parameter is not supported when parsing the tree.
        if ("discover__partitions".equals(paramKeyName)) {
          paramKeyName = "discover.partitions";
          keyName = "this.parameters.get(\"" + paramKeyName + "\").toUpperCase()";
          value = value.toString().toUpperCase();
        }
      } else {
        filterBuilder.setError("Invalid key name in filter.  " +
          "Use constants from org.apache.hadoop.hive.metastore.api");
        return;
      }
      generateJDOFilterGeneral(params, filterBuilder);
    }

    /**
     * Generates a general filter.  Given a map of <key, value>,
     * generates a statement of the form:
     * key1 operator value2 (&& | || ) key2 operator value2 ...
     *
     * Currently supported types for value are String and Long.
     * The LIKE operator for Longs is unsupported.
     */
    private void generateJDOFilterGeneral(Map<String, Object> params,
        FilterBuilder filterBuilder) throws MetaException {
      String paramName = PARAM_PREFIX + params.size();
      params.put(paramName, value);

      if (isReverseOrder) {
        if (operator == Operator.LIKE) {
          filterBuilder.setError("Value should be on the RHS for LIKE operator : " +
              "Key <" + keyName + ">");
        } else {
          filterBuilder.append(paramName + " " + operator.getJdoOp() + " " + keyName);
        }
      } else {
        if (operator == Operator.LIKE) {
          filterBuilder.append(" " + keyName + "." + operator.getJdoOp() + "(" + paramName + ") ");
        } else {
          filterBuilder.append(" " + keyName + " " + operator.getJdoOp() + " " + paramName);
        }
      }
    }

    private void generateJDOFilterOverPartitions(Configuration conf,
                                                 Map<String, Object> params, FilterBuilder filterBuilder, List<FieldSchema> partitionKeys) throws MetaException {
      int partitionColumnCount = partitionKeys.size();
      int partitionColumnIndex = LeafNode.getPartColIndexForFilter(keyName, partitionKeys, filterBuilder);
      if (filterBuilder.hasError()) return;

      boolean canPushDownIntegral =
          MetastoreConf.getBoolVar(conf, MetastoreConf.ConfVars.INTEGER_JDO_PUSHDOWN);
      String valueAsString = getJdoFilterPushdownParam(
              partitionColumnIndex, filterBuilder, canPushDownIntegral, partitionKeys);
      if (filterBuilder.hasError()) return;

      String paramName = PARAM_PREFIX + params.size();
      if (operator == Operator.LIKE) {
        params.put(paramName, makeJdoFilterForLike(valueAsString));
      } else {
        params.put(paramName, valueAsString);
      }

      boolean isOpEquals = Operator.isEqualOperator(operator);
      if (isOpEquals || Operator.isNotEqualOperator(operator)) {
        String partitionKey = partitionKeys.get(partitionColumnIndex).getName();
        makeFilterForEquals(partitionKey, valueAsString, paramName, params,
            partitionColumnIndex, partitionColumnCount, isOpEquals, filterBuilder);
        return;
      }
      //get the value for a partition key form MPartition.values (PARTITION_KEY_VALUES)
      String valString = "values.get(" + partitionColumnIndex + ")";

      if (operator == Operator.LIKE) {
        if (isReverseOrder) {
          // For LIKE, the value should be on the RHS.
          filterBuilder.setError(
              "Value should be on the RHS for LIKE operator : Key <" + keyName + ">");
        }
        // TODO: in all likelihood, this won't actually work. Keep it for backward compat.
        filterBuilder.append(" " + valString + "." + operator.getJdoOp() + "(" + paramName + ") ");
      } else {
        filterBuilder.append(isReverseOrder
            ? paramName + " " + operator.getJdoOp() + " " + valString
            : " " + valString + " " + operator.getJdoOp() + " " + paramName);
      }
    }

    private static String makeJdoFilterForLike(String likePattern) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < likePattern.length(); i++) {
        // Make a special case for "\\_" and "\\%"
        char n = likePattern.charAt(i);
        if (n == '\\'
            && i + 1 < likePattern.length()
            && (likePattern.charAt(i + 1) == '_' || likePattern.charAt(i + 1) == '%')) {
          sb.append(likePattern.charAt(i + 1));
          i++;
          continue;
        }

        if (n == '_') {
          sb.append(".");
        } else if (n == '%') {
          sb.append(".*");
        } else {
          sb.append(likePattern.charAt(i));
        }
      }
      return sb.toString();
    }

    /**
     * @return true iff filter pushdown for this operator can be done for integral types.
     */
    public boolean canJdoUseStringsWithIntegral() {
      return (operator == Operator.EQUALS)
          || (operator == Operator.NOTEQUALS)
          || (operator == Operator.NOTEQUALS2);
    }

    /**
     * Validates and gets the query parameter for JDO filter pushdown based on the column
     * and the constant stored in this node.
     * @param partitionKeys
     * @param partColIndex The index of the column to check.
     * @param filterBuilder filter builder used to report error, if any.
     * @return The parameter string.
     */
    private String getJdoFilterPushdownParam(int partColIndex,
                                             FilterBuilder filterBuilder, boolean canPushDownIntegral, List<FieldSchema> partitionKeys) throws MetaException {
      boolean isIntegralSupported = canPushDownIntegral && canJdoUseStringsWithIntegral();
      String colType = ColumnType.getTypeName(partitionKeys.get(partColIndex).getType());
      // Can only support partitions whose types are string, or maybe integers
      // Date/Timestamp data type value is considered as string hence pushing down to JDO.
      if (!ColumnType.StringTypes.contains(colType) && !ColumnType.DATE_TYPE_NAME.equalsIgnoreCase(colType)
          && !ColumnType.TIMESTAMP_TYPE_NAME.equalsIgnoreCase(colType)
          && (!isIntegralSupported || !ColumnType.IntegralTypes.contains(colType))) {
        filterBuilder.setError("Filtering is supported only on partition keys of type " +
            "string" + (isIntegralSupported ? ", or integral types" : ""));
        return null;
      }

      // There's no support for date or timestamp cast in JDO. Let's convert it to string; the date
      // columns have been excluded above, so it will either compare w/string or fail.
      Object val = value;
      if (colType.equals("date") && value instanceof Date) {
        val = MetaStoreUtils.convertDateToString((Date)value);
      } else if (colType.equals("timestamp") && value instanceof Timestamp) {
        val = MetaStoreUtils.convertTimestampToString((Timestamp)value);
      }
      boolean isStringValue = val instanceof String;
      if (!isStringValue && (!isIntegralSupported || !(val instanceof Long))) {
        filterBuilder.setError("Filtering is supported only on partition keys of type " +
            "string" + (isIntegralSupported ? ", or integral types" : ""));
        return null;
      }

      return isStringValue ? (String)val : Long.toString((Long)val);
    }
  }

  public void accept(TreeVisitor treeVisitor) throws MetaException {
    if (this.root != null) {
      this.root.accept(treeVisitor);
    }
  }

  /**
   * For equals and not-equals, we can make the JDO query much faster by filtering
   * based on the partition name. For a condition like ds="2010-10-01", we can see
   * if there are any partitions with a name that contains the substring "ds=2010-10-01/"
   * False matches aren't possible since "=" is escaped for partition names
   * and the trailing '/' ensures that we won't get a match with ds=2010-10-011
   * Note that filters on integral type equality also work correctly by virtue of
   * comparing them as part of ds=1234 string.
   *
   * Two cases to keep in mind: Case with only one partition column (no '/'s)
   * Case where the partition key column is at the end of the name. (no
   * tailing '/')
   *
   * @param keyName name of the partition column e.g. ds.
   * @param value The value to compare to.
   * @param paramName name of the parameter to use for JDOQL.
   * @param params a map from the parameter name to their values.
   * @param keyPos The index of the requisite partition column in the list of such columns.
   * @param keyCount Partition column count for the table.
   * @param isEq whether the operator is equals, or not-equals.
   * @param fltr Filter builder used to append the filter, or report errors.
   */
  private static void makeFilterForEquals(String keyName, String value, String paramName,
      Map<String, Object> params, int keyPos, int keyCount, boolean isEq, FilterBuilder fltr)
          throws MetaException {
    Map<String, String> partKeyToVal = new HashMap<>();
    partKeyToVal.put(keyName, value);
    // If a partition has multiple partition keys, we make the assumption that
    // makePartName with one key will return a substring of the name made
    // with both all the keys.
    String escapedNameFragment = Warehouse.makePartName(partKeyToVal, false);
    if (keyCount == 1) {
      // Case where this is no other partition columns
      params.put(paramName, escapedNameFragment);
      fltr.append("partitionName ").append(isEq ? "== " : "!= ").append(paramName);
    } else if (keyPos + 1 == keyCount) {
      // Case where the partition column is at the end of the name. There will
      // be a leading '/' but no trailing '/'
      params.put(paramName, "/" + escapedNameFragment);
      fltr.append(isEq ? "" : "!").append("partitionName.endsWith(")
        .append(paramName).append(")");
    } else if (keyPos == 0) {
      // Case where the partition column is at the beginning of the name. There will
      // be a trailing '/' but no leading '/'
      params.put(paramName, escapedNameFragment + "/");
      fltr.append(isEq ? "" : "!").append("partitionName.startsWith(")
        .append(paramName).append(")");
    } else {
      // Case where the partition column is in the middle of the name. There will
      // be a leading '/' and an trailing '/'
      params.put(paramName, "/" + escapedNameFragment + "/");
      fltr.append("partitionName.indexOf(").append(paramName).append(")")
        .append(isEq ? ">= 0" : "< 0");
    }
  }

  /**
   * The root node for the tree.
   */
  private TreeNode root = null;

  /**
   * The node stack used to keep track of the tree nodes during parsing.
   */
  private final Stack<TreeNode> nodeStack = new Stack<>();

  public TreeNode getRoot() {
    return this.root;
  }

  public void setRoot(TreeNode tn) {
    this.root = tn;
  }


  /**
   * Adds a intermediate node of either type(AND/OR). Pops last two nodes from
   * the stack and sets them as children of the new node and pushes itself
   * onto the stack.
   * @param andOr the operator type
   */
  public void addIntermediateNode(LogicalOperator andOr) {

    TreeNode rhs = nodeStack.pop();
    TreeNode lhs = nodeStack.pop();
    TreeNode newNode = new TreeNode(lhs, andOr, rhs);
    nodeStack.push(newNode);
    root = newNode;
  }

  /**
   * Adds a leaf node, pushes the new node onto the stack.
   * @param newNode the new node
   */
  public void addLeafNode(LeafNode newNode) {
    if( root == null ) {
      root = newNode;
    }
    nodeStack.push(newNode);
  }

}
