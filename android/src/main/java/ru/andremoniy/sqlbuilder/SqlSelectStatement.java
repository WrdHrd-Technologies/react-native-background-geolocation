package ru.andremoniy.sqlbuilder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ru.andremoniy.utils.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class SqlSelectStatement implements SqlStatement {

    private static final class JoinElement {
        final String joinDeclaration;
        final List<ConditionClause> conditions = new ArrayList<>();
        final List<String> usingColumns = new ArrayList<>();

        JoinElement(String joinDeclaration) {
            this.joinDeclaration = joinDeclaration;
        }
    }

    private static final class ConditionClause {
        final String connector;
        final String expression;

        ConditionClause(String connector, String expression) {
            this.connector = connector;
            this.expression = expression;
        }
    }

    private boolean _distinct = false;
    private String _all = "*";

    private final List<String> _column = new ArrayList<>();
    private final List<String> _table = new ArrayList<>();
    private final List<JoinElement> _join = new ArrayList<>();
    private final List<ConditionClause> _where = new ArrayList<>();
    private final List<String> _groupBy = new ArrayList<>();
    private final List<ConditionClause> _having = new ArrayList<>();
    private final List<String> _orderBy = new ArrayList<>();
    private final List<String> _combine = new ArrayList<>();

    private int _limit = 0;
    private int _offset = 0;

    public SqlSelectStatement() {
    }

    public void distinct(boolean distinct) {
        this._distinct = distinct;
    }

    public void all(@Nullable String all) {
        if (all != null) {
            this._all = SqlExpression.prepareIdentifier(all);
            if (!this._all.endsWith(".*") && !"*".equals(this._all)) {
                this._all = this._all + ".*";
            }
        } else {
            this._all = "*";
        }
        this._column.clear();
    }

    public void column(@NonNull Object column) {
        this._column.add(SqlExpression.prepareIdentifier(column));
    }

    public void column(@NonNull Object column, @NonNull String alias) {
        this._column.add(SqlExpression.prepareIdentifier(column) + " AS " + SqlExpression.prepareAlias(alias));
    }

    public void columns(@NonNull String[] columns) {
        for (String column : columns) {
            if (column != null) {
                this._column.add(SqlExpression.prepareIdentifier(column));
            }
        }
    }

    public void from(@NonNull String table) {
        this._table.add(SqlExpression.prepareIdentifier(table));
    }

    public void from(@NonNull String table, @NonNull String alias) {
        this._table.add(SqlExpression.prepareIdentifier(table) + " " + SqlExpression.prepareAlias(alias));
    }

    public void join(@NonNull String table) {
        join(table, SqlExpression.SqlJoinTypeInner);
    }

    public void join(@NonNull String table, @NonNull String alias) {
        join(table, alias, SqlExpression.SqlJoinTypeInner);
    }

    public void join(@NonNull String table, @NonNull Object type) {
        String joinType = (type instanceof String) ? (String) type : SqlExpression.SqlJoinTypeInner;
        if (joinType.trim().isEmpty()) {
            joinType = SqlExpression.SqlJoinTypeInner;
        }

        String joinDecl = joinType.trim().toUpperCase(Locale.US) + " JOIN " + SqlExpression.prepareIdentifier(table);
        this._join.add(new JoinElement(joinDecl));
    }

    public void join(@NonNull String table, @NonNull String alias, @NonNull String type) {
        String joinType = (type != null && !type.trim().isEmpty()) ? type : SqlExpression.SqlJoinTypeInner;

        String joinDecl = joinType.trim().toUpperCase(Locale.US) + " JOIN " + SqlExpression.prepareIdentifier(table) + " " + SqlExpression.prepareAlias(alias);
        this._join.add(new JoinElement(joinDecl));
    }

    public void joinOn(@NonNull String column1, @Nullable String operator, @NonNull String column2) {
        joinOn(column1, operator, column2, SqlExpression.SqlConnectorAnd);
    }

    public void joinOn(@NonNull String column1, @Nullable String operator, @NonNull String column2, @NonNull String connector) {
        if (_join.isEmpty()) {
            throw new IllegalArgumentException("Must declare a JOIN clause before appending a conditional constraint mapping loop.");
        }

        JoinElement lastJoin = _join.get(_join.size() - 1);
        if (!lastJoin.usingColumns.isEmpty()) {
            throw new IllegalArgumentException("May not mix USING and ON constraints across the same single JOIN boundary block.");
        }

        String opStr = (operator != null) ? operator.toUpperCase(Locale.US) : "";
        String expression = SqlExpression.prepareIdentifier(column1) + " " + opStr + " " + SqlExpression.prepareIdentifier(column2);

        lastJoin.conditions.add(new ConditionClause(SqlExpression.prepareConnector(connector), expression));
    }

    public void where(@NonNull String column1, @Nullable String operator, @NonNull String column2) {
        where(column1, operator, column2, SqlExpression.SqlConnectorAnd);
    }

    public void where(@NonNull String column1, @Nullable String operator, @NonNull String column2, @NonNull String connector) {
        String opStr = (operator != null) ? operator.toUpperCase(Locale.US) : "";
        String expression = SqlExpression.prepareIdentifier(column1) + " " + opStr + " " + SqlExpression.prepareIdentifier(column2);

        this._where.add(new ConditionClause(SqlExpression.prepareConnector(connector), expression));
    }

    public void where(@NonNull String column, @Nullable String operator, @Nullable Object value) {
        where(column, operator, value, SqlExpression.SqlConnectorAnd);
    }

    public void where(@NonNull String column, @Nullable String operator, @Nullable Object value, @NonNull String connector) {
        if (operator == null) return;

        String opUpper = operator.toUpperCase(Locale.US);
        String field = SqlExpression.prepareIdentifier(column);
        String preparedConnector = SqlExpression.prepareConnector(connector);

        if (SqlExpression.SqlOperatorIn.equals(opUpper) || "NOT IN".equals(opUpper)) {
            if (value == null || !value.getClass().isArray()) {
                throw new IllegalArgumentException("The array specification operator requires value array mapping contexts.");
            }
            this._where.add(new ConditionClause(preparedConnector, field + " " + opUpper + " " + SqlExpression.prepareValue(value)));

        } else if ("BETWEEN".equals(opUpper) || "NOT BETWEEN".equals(opUpper)) {
            if (value == null || !value.getClass().isArray() || ((Object[]) value).length < 2) {
                throw new IllegalArgumentException("The boundary selection constraint operator requires an explicit range block boundary pair.");
            }
            Object[] range = (Object[]) value;
            String expr = field + " " + opUpper + " " + SqlExpression.prepareValue(range[0]) + " AND " + SqlExpression.prepareValue(range[1]);
            this._where.add(new ConditionClause(preparedConnector, expr));

        } else {
            if (SqlExpression.NULL.equals(value) || value == null) {
                if (SqlExpression.SqlOperatorEqualTo.equals(opUpper)) {
                    opUpper = "IS";
                } else if (SqlExpression.SqlOperatorNotEqualTo.equals(opUpper) || "!=".equals(opUpper)) {
                    opUpper = "IS NOT";
                }
            }
            this._where.add(new ConditionClause(preparedConnector, field + " " + opUpper + " " + SqlExpression.prepareValue(value)));
        }
    }

    public void orderBy(@NonNull String column) {
        orderBy(column, false, null);
    }

    public void orderBy(@NonNull String column, boolean descending) {
        orderBy(column, descending, null);
    }

    public void orderBy(@NonNull String column, @Nullable String weight) {
        orderBy(column, false, weight);
    }

    public void orderBy(@NonNull String column, boolean descending, @Nullable String weight) {
        String field = SqlExpression.prepareIdentifier(column);
        String order = SqlExpression.prepareSortOrder(descending);
        String preparedWeight = SqlExpression.prepareSortWeight(weight);

        if ("FIRST".equals(preparedWeight)) {
            this._orderBy.add("CASE WHEN " + field + " IS NULL THEN 0 ELSE 1 END, " + field + " " + order);
        } else if ("LAST".equals(preparedWeight)) {
            this._orderBy.add("CASE WHEN " + field + " IS NULL THEN 1 ELSE 0 END, " + field + " " + order);
        } else {
            this._orderBy.add(field + " " + order);
        }
    }

    public void limit(int limit) {
        this._limit = limit;
    }

    public void limit(int limit, int offset) {
        this._limit = limit;
        this._offset = offset;
    }

    public void offset(int offset) {
        this._offset = offset;
    }

    @NonNull
    @Override
    public String statement() {
        StringBuilder b = new StringBuilder(512);
        b.append("SELECT ");

        if (_distinct) {
            b.append("DISTINCT ");
        }

        if (!_column.isEmpty()) {
            b.append(TextUtils.join(", ", _column));
        } else {
            b.append(_all);
        }

        if (!_table.isEmpty()) {
            b.append(" FROM ").append(TextUtils.join(", ", _table));
        }

        for (JoinElement join : _join) {
            b.append(" ").append(join.joinDeclaration);
            if (!join.conditions.isEmpty()) {
                b.append(" ON (");
                for (int i = 0; i < join.conditions.size(); i++) {
                    ConditionClause cond = join.conditions.get(i);
                    if (i > 0) {
                        b.append(" ").append(cond.connector).append(" ");
                    }
                    b.append(cond.expression);
                }
                b.append(")");
            } else if (!join.usingColumns.isEmpty()) {
                b.append(" USING (").append(TextUtils.join(", ", join.usingColumns)).append(")");
            }
        }

        if (!_where.isEmpty()) {
            b.append(" WHERE ");
            appendConditions(b, _where);
        }

        if (!_groupBy.isEmpty()) {
            b.append(" GROUP BY ").append(TextUtils.join(", ", _groupBy));
        }

        if (!_having.isEmpty()) {
            b.append(" HAVING ");
            appendConditions(b, _having);
        }

        if (!_orderBy.isEmpty()) {
            b.append(" ORDER BY ").append(TextUtils.join(", ", _orderBy));
        }

        if (_limit > 0) {
            b.append(" LIMIT ").append(_limit);
        }

        if (_offset > 0) {
            b.append(" OFFSET ").append(_offset);
        }

        for (String combine : _combine) {
            b.append(" ").append(combine);
        }

        b.append(";");
        return b.toString();
    }

    private void appendConditions(StringBuilder b, List<ConditionClause> conditions) {
        boolean doAppendConnector = false;
        for (ConditionClause clause : conditions) {
            if (doAppendConnector && !")".equals(clause.expression)) {
                b.append(" ").append(clause.connector).append(" ");
            }
            b.append(clause.expression);
            doAppendConnector = !"(".equals(clause.expression);
        }
    }
}