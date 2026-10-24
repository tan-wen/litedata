package com.litedata.metric;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Small expression languages: neither SQL fragments nor scripts are evaluated. */
public final class MetricExpressions {
    private MetricExpressions() {}
    private static final Pattern FORMULA_TOKEN = Pattern.compile("\\s*(\\[[a-z_][a-z0-9_]{0,63}\\]|[0-9]+(?:\\.[0-9]+)?|[()+*/-])");
    private static final Pattern FILTER_TOKEN = Pattern.compile("\\s*('(?:[^'\\\\]|'')*'|>=|<=|<>|!=|[=><(),]|-?[0-9]+(?:\\.[0-9]+)?|[a-zA-Z_][a-zA-Z0-9_]*)");

    public static Set<String> references(String formula) {
        var references = new LinkedHashSet<String>();
        formula(formula, code -> { references.add(code); return "metric_value"; });
        if (references.isEmpty() || references.size() > 10)
            throw invalid("复合公式须引用 1 到 10 个指标，使用 [指标编码]");
        return references;
    }
    public static String formula(String input, Function<String,String> reference) {
        var parser = new Arithmetic(tokens(input, FORMULA_TOKEN), reference);
        String result = parser.sum(0); parser.end(); return result;
    }
    public static String filter(String input, Set<String> columns) {
        if (input == null || input.isBlank()) return "";
        var parser = new Predicate(tokens(input, FILTER_TOKEN), columns);
        String result = parser.or(0); parser.end(); return result;
    }
    /** Reuses the filter grammar so quoted literal text is never mistaken for a field. */
    public static Set<String> filterColumns(String input) {
        if (input == null || input.isBlank()) return Set.of();
        var tokens = tokens(input, FILTER_TOKEN);
        var columns = new LinkedHashSet<String>();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.matches("[a-zA-Z_][a-zA-Z0-9_]*") && i + 1 < tokens.size()
                    && Set.of("=", "!=", "<>", ">", ">=", "<", "<=", "IS", "IN").contains(tokens.get(i + 1).toUpperCase(Locale.ROOT)))
                columns.add(token.toLowerCase(Locale.ROOT));
        }
        filter(input, columns);
        return Collections.unmodifiableSet(columns);
    }
    private static List<String> tokens(String input, Pattern pattern) {
        if (input == null || input.isBlank() || input.length() > 2000) throw invalid("表达式不能为空且最长 2000 字符");
        input = input.trim(); var result = new ArrayList<String>();
        var matcher = pattern.matcher(input); int cursor = 0;
        while (cursor < input.length()) {
            matcher.region(cursor, input.length());
            if (!matcher.lookingAt()) throw invalid("表达式包含不支持的内容，请检查公式或筛选条件");
            result.add(matcher.group(1)); cursor = matcher.end();
            if (result.size() > 200) throw invalid("表达式过于复杂，最多 200 个词项");
        }
        return result;
    }
    private static class Parser {
        final List<String> tokens; int index;
        Parser(List<String> tokens) { this.tokens = tokens; }
        boolean take(String expected) {
            if (index < tokens.size() && expected.equalsIgnoreCase(tokens.get(index))) { index++; return true; }
            return false;
        }
        String next() { if (index >= tokens.size()) throw invalid("表达式不完整"); return tokens.get(index++); }
        void expect(String expected) { if (!take(expected)) throw invalid("表达式缺少 " + expected); }
        void end() { if (index != tokens.size()) throw invalid("表达式包含多余内容"); }
        void depth(int depth) { if (depth > 32) throw invalid("表达式括号嵌套过深"); }
    }
    private static class Arithmetic extends Parser {
        final Function<String,String> reference;
        Arithmetic(List<String> tokens, Function<String,String> reference) { super(tokens); this.reference = reference; }
        String sum(int depth) {
            String value = product(depth);
            while (index < tokens.size()) {
                if (take("+")) value = "(" + value + " + " + product(depth) + ")";
                else if (take("-")) value = "(" + value + " - " + product(depth) + ")";
                else break;
            }
            return value;
        }
        String product(int depth) {
            String value = atom(depth);
            while (index < tokens.size()) {
                if (take("*")) value = "(" + value + " * " + atom(depth) + ")";
                else if (take("/")) value = "(" + value + " / NULLIF(" + atom(depth) + ", 0))";
                else break;
            }
            return value;
        }
        String atom(int depth) {
            depth(depth);
            if (take("(")) { String value = sum(depth + 1); expect(")"); return "(" + value + ")"; }
            if (take("-")) return "(-" + atom(depth + 1) + ")";
            if (take("+")) return atom(depth + 1);
            String token = next();
            if (token.startsWith("[")) return reference.apply(token.substring(1, token.length() - 1));
            return number(token);
        }
    }
    private static class Predicate extends Parser {
        final Set<String> columns;
        Predicate(List<String> tokens, Set<String> columns) { super(tokens); this.columns = columns; }
        String or(int depth) {
            depth(depth); String value = and(depth);
            while (take("OR")) value = "(" + value + " OR " + and(depth) + ")";
            return value;
        }
        String and(int depth) {
            String value = comparison(depth);
            while (take("AND")) value = "(" + value + " AND " + comparison(depth) + ")";
            return value;
        }
        String comparison(int depth) {
            if (take("(")) { String value = or(depth + 1); expect(")"); return "(" + value + ")"; }
            String column = next().toLowerCase(Locale.ROOT);
            if (!columns.contains(column)) throw invalid("筛选字段不在来源模型中：" + column);
            String left = "`" + column + "`";
            if (take("IS")) { boolean not = take("NOT"); expect("NULL"); return left + (not ? " IS NOT NULL" : " IS NULL"); }
            if (take("IN")) {
                expect("("); var values = new ArrayList<String>(); values.add(literal());
                while (take(",")) { if (values.size() >= 30) throw invalid("IN 条件最多 30 个值"); values.add(literal()); }
                expect(")"); return left + " IN (" + String.join(", ", values) + ")";
            }
            String operator = next();
            if (!Set.of("=", "!=", "<>", ">", ">=", "<", "<=").contains(operator)) throw invalid("仅支持比较、IN、IS NULL 及 AND/OR 条件");
            return left + " " + operator + " " + literal();
        }
        String literal() {
            String value = next();
            if (value.startsWith("'")) {
                if (value.chars().anyMatch(c -> c < 32)) throw invalid("条件值不能包含控制字符");
                return value;
            }
            return number(value);
        }
    }
    private static String number(String token) {
        if (!token.matches("-?[0-9]+(?:\\.[0-9]+)?") || token.length() > 38) throw invalid("表达式需要数字或指标引用");
        return new BigDecimal(token).toPlainString();
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
