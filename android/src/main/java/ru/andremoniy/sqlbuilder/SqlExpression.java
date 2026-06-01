package ru.andremoniy.sqlbuilder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

public class SqlExpression {
    public static final String NULL = "NULL";

    // Block Statement Tokens
    public static final String SqlEnclosureOpeningBrace = "(";
    public static final String SqlEnclosureClosingBrace = ")";

    // Connectors
    public static final String SqlConnectorAnd = "AND";
    public static final String SqlConnectorOr = "OR";

    // Join Types (Exposed directly for explicit statement compilation)
    public static final String SqlJoinTypeCross = "CROSS";
    public static final String SqlJoinTypeInner = "INNER";
    public static final String SqlJoinTypeLeft = "LEFT";
    public static final String SqlJoinTypeLeftOuter = "LEFT OUTER";
    public static final String SqlJoinTypeNatural = "NATURAL";
    public static final String SqlJoinTypeNaturalCross = "NATURAL CROSS";
    public static final String SqlJoinTypeNaturalInner = "NATURAL INNER";
    public static final String SqlJoinTypeNaturalLeft = "NATURAL LEFT";
    public static final String SqlJoinTypeNaturalLeftOuter = "NATURAL LEFT OUTER";
    public static final String SqlJoinTypeNone = "";

    // Operators
    public static final String SqlOperatorLessThan = "<";
    public static final String SqlOperatorLessThanOrEqualTo = "<=";
    public static final String SqlOperatorGreaterThan = ">";
    public static final String SqlOperatorGreaterThanOrEqualTo = ">=";
    public static final String SqlOperatorEqualTo = "=";
    public static final String SqlOperatorNotEqualTo = "<>";
    public static final String SqlOperatorIn = "IN";
    public static final String SqlOperatorNotIn = "NOT IN";

    // Order Operators (for Nulls)
    public static final String SqlNullsFirst = "FIRST";
    public static final String SqlNullsLast = "LAST";

    // Declared Datatype Strings
    public static final String SqlDataTypeInteger = "INTEGER";
    public static final String SqlDataTypeText = "TEXT";

    private static final Pattern CLEAN_PATTERN = Pattern.compile("[^a-zA-Z0-9_]");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("[\\n\\r\\s]+");
    private static final Pattern WILDCARD_PATTERN = Pattern.compile("^\\s*\\*\\s*$");

    private static final ThreadLocal<DateFormat> DATE_FORMAT_THREAD_LOCAL = ThreadLocal.withInitial(() ->
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US));

    protected final String _expression;

    public SqlExpression() {
        this._expression = "";
    }

    public SqlExpression(@Nullable String sql) {
        this._expression = (sql != null) ? sql : "";
    }

    @NonNull
    public String expression() {
        return _expression;
    }

    @NonNull
    public static SqlExpression sql(@Nullable String sql) {
        return new SqlExpression(sql);
    }

    @NonNull
    public static String prepareAlias(@Nullable String token) {
        if (token == null) return "";
        String clean = CLEAN_PATTERN.matcher(token).replaceAll("");
        clean = WHITESPACE_PATTERN.matcher(clean).replaceAll("");
        return "[" + clean + "]";
    }

    @NonNull
    public static String prepareConnector(@Nullable String token) {
        if (token == null) return "";
        String upperToken = token.toUpperCase(Locale.US);
        if (upperToken.matches("^(AND|OR)?I$")) {
            throw new IllegalArgumentException("Invalid connector token provided: " + token);
        }
        return upperToken;
    }

    @NonNull
    public static String prepareEnclosure(@Nullable String token) {
        if (!(SqlEnclosureOpeningBrace.equals(token) || SqlEnclosureClosingBrace.equals(token))) {
            throw new IllegalArgumentException("Invalid enclosure token provided: " + token);
        }
        return (token != null) ? token : "";
    }

    @NonNull
    public static String prepareIdentifier(@Nullable Object identifier) {
        if (identifier == null) return "";

        if (identifier instanceof String) {
            String idStr = (String) identifier;
            if (idStr.indexOf('.') == -1) {
                if (WILDCARD_PATTERN.matcher(idStr).matches()) return "*";
                String clean = CLEAN_PATTERN.matcher(idStr).replaceAll("");
                return "[" + WHITESPACE_PATTERN.matcher(clean).replaceAll("") + "]";
            }

            StringBuilder buffer = new StringBuilder(idStr.length() + 16);
            String[] tokens = idStr.split("\\.");

            for (int i = 0; i < tokens.length; i++) {
                if (i > 0) buffer.append(".");
                String token = tokens[i];

                if (WILDCARD_PATTERN.matcher(token).matches()) {
                    buffer.append("*");
                } else {
                    String clean = CLEAN_PATTERN.matcher(token).replaceAll("");
                    clean = WHITESPACE_PATTERN.matcher(clean).replaceAll("");
                    buffer.append("[").append(clean).append("]");
                }
            }
            return buffer.toString();
        }

        if (identifier instanceof SqlExpression) {
            return ((SqlExpression) identifier).expression();
        }

        if (identifier instanceof SqlStatement) {
            String statement = ((SqlStatement) identifier).statement();
            if (statement.endsWith(";")) {
                statement = statement.substring(0, statement.length() - 1);
            }
            return "(" + statement + ")";
        }

        throw new IllegalArgumentException("Unable to prepare identifier: " + identifier.getClass().getName());
    }

    @NonNull
    public static String prepareSortOrder(boolean descending) {
        return descending ? "DESC" : "ASC";
    }

    @NonNull
    public static String prepareSortWeight(@Nullable String weight) {
        if (weight != null) {
            String upperWeight = weight.toUpperCase(Locale.US);
            if (!upperWeight.matches("^(FIRST|LAST)$")) {
                throw new IllegalArgumentException("Invalid weight token provided: " + weight);
            }
            return upperWeight;
        }
        return "DEFAULT";
    }

    @NonNull
    public static String prepareValue(@Nullable Object value) {
        if (value == null || NULL.equals(value)) {
            return "NULL";
        }

        if (value.getClass().isArray()) {
            Object[] array = (Object[]) value;
            if (array.length == 0) return "()";

            StringBuilder buffer = new StringBuilder(array.length * 16);
            buffer.append("(");
            for (int i = 0; i < array.length; i++) {
                if (i > 0) buffer.append(", ");
                buffer.append(prepareValue(array[i]));
            }
            buffer.append(")");
            return buffer.toString();
        }

        if (value instanceof Number) {
            return String.valueOf(value);
        }

        if (value instanceof String) {
            String strValue = (String) value;
            String escaped = strValue.replace("'", "''");
            return "'" + escaped + "'";
        }

        if (value instanceof Date) {
            DateFormat df = DATE_FORMAT_THREAD_LOCAL.get();
            return "'" + df.format((Date) value) + "'";
        }

        if (value instanceof SqlExpression) {
            return ((SqlExpression) value).expression();
        }

        if (value instanceof SqlSelectStatement) {
            String statement = ((SqlSelectStatement) value).statement();
            if (statement.endsWith(";")) {
                statement = statement.substring(0, statement.length() - 1);
            }
            return "(" + statement + ")";
        }

        throw new IllegalArgumentException("Unable to prepare value type: " + value.getClass().getName());
    }
}