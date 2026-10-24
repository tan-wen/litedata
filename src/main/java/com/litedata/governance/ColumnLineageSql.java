package com.litedata.governance;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.*;

/** A deliberately small, non-executing column lineage reader for INSERT ... SELECT. */
public final class ColumnLineageSql {
    /* These syntax forms are intentionally outside this bounded lineage reader. */
    private static final Pattern UNSUPPORTED = Pattern.compile("(?is)\\b(with|union|natural|using)\\b|\\(\\s*select\\b|\\border\\s+by\\b|\\blimit\\b|\\bfetch\\b|\\boffset\\b|\\bover\\s*\\(|\\btop\\s*(?:\\(|\\d)");
    private ColumnLineageSql() {}

    public record Result(String target, List<Projection> columns, List<String> warnings) {}
    public record Projection(String column, String expression, List<Input> inputs) {}
    public record Input(String table, String column, String role, String expression) {
        public Input(String table, String column, String role) { this(table, column, role, ""); }
    }

    public static Result parse(String sql, List<String> targetColumns, Function<String, List<String>> schema) {
        if (sql == null || sql.isBlank() || sql.length() > 20000 || sql.contains(";") || UNSUPPORTED.matcher(sql).find())
            throw unsupported();
        Objects.requireNonNull(targetColumns, "targetColumns");
        Objects.requireNonNull(schema, "schema");
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Insert insert) || insert.getSelect() == null || insert.getSelect() instanceof Values)
                throw unsupported();
            if (insert.isOverwrite() || insert.isUseDuplicate() || insert.isUseSet() || insert.isModifierIgnore()) throw unsupported();
            String target = table(insert.getTable().getFullyQualifiedName());
            Select select = insert.getSelect();
            PlainSelect plain = select.getPlainSelect();
            if (plain == null || plain.getJoins() != null && plain.getJoins().stream().anyMatch(Join::isSimple)) throw unsupported();

            Sources sources = sources(plain, schema);
            List<String> explicit = insert.getColumns() == null ? List.of() : insert.getColumns().stream().map(c -> column(c.getColumnName())).toList();
            List<Item> items = expand(plain.getSelectItems(), sources);
            List<String> targets = explicit.isEmpty() ? cleanTargets(targetColumns) : explicit;
            if (items.size() != targets.size()) throw new IllegalArgumentException("目标列与 SELECT 投影数量不一致");

            Set<String> outputAliases = plain.getSelectItems().stream().map(item -> item.getAlias()).filter(Objects::nonNull)
                    .map(alias -> column(alias.getName())).collect(java.util.stream.Collectors.toSet());
            List<Dependency> conditions = new ArrayList<>();
            add(conditions, text(plain.getWhere()), "SQL 筛选", sources);
            if (plain.getJoins() != null) for (Join join : plain.getJoins()) {
                if (join.getOnExpressions() != null) for (var on : join.getOnExpressions()) add(conditions, text(on), "SQL 关联", sources);
            }
            if (plain.getGroupBy() != null && plain.getGroupBy().getGroupByExpressions() != null)
                for (Object candidate : plain.getGroupBy().getGroupByExpressions()) {
                    if (!(candidate instanceof Expression group)) throw unsupported();
                    if (group.toString().trim().matches("\\d+") || containsOutputAlias(group, outputAliases)) throw unsupported();
                    add(conditions, text(group), "SQL 分组", sources);
                }
            if (plain.getHaving() != null && containsOutputAlias(plain.getHaving(), outputAliases)) throw unsupported();
            add(conditions, text(plain.getHaving()), "SQL HAVING", sources);

            List<Projection> result = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                Item item = items.get(i);
                List<Input> inputs = new ArrayList<>();
                List<Dependency> expressionInputs = dependencies(item.expression, "表达式", sources);
                for (Dependency d : expressionInputs) inputs.add(new Input(d.table, d.column, d.role, d.expression));
                if ((expressionInputs.isEmpty() && !sources.all.isEmpty()) || sources.all.size() > 1)
                    for (Source source : sources.all) inputs.add(new Input(source.name, "*", "SQL 行集", "FROM " + source.name));
                for (Dependency d : conditions) inputs.add(new Input(d.table, d.column, d.role, d.expression));
                result.add(new Projection(targets.get(i), item.expression, List.copyOf(inputs)));
            }
            return new Result(target, List.copyOf(result), List.of());
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("无法解析列级血缘 SQL", e); }
    }

    private static Sources sources(PlainSelect plain, Function<String, List<String>> schema) {
        List<Source> all = new ArrayList<>();
        if (plain.getFromItem() != null) addSource(all, plain.getFromItem(), schema);
        if (plain.getJoins() != null) for (Join join : plain.getJoins()) addSource(all, join.getRightItem(), schema);
        return new Sources(all);
    }
    private static void addSource(List<Source> out, FromItem item, Function<String, List<String>> schema) {
        if (!(item instanceof Table source)) throw unsupported();
        String name = table(source.getFullyQualifiedName());
        String alias = source.getAlias() == null ? name : column(source.getAlias().getName());
        if (out.stream().anyMatch(s -> s.alias.equals(alias))) throw new IllegalArgumentException("重复的表别名");
        List<String> raw = schema.apply(name);
        List<String> fields = raw == null || raw.isEmpty() ? null : raw.stream().filter(Objects::nonNull).map(ColumnLineageSql::column).toList();
        out.add(new Source(name, alias, fields));
    }
    private static List<Item> expand(List<SelectItem<?>> selectItems, Sources sources) {
        List<Item> result = new ArrayList<>();
        for (SelectItem<?> select : selectItems) {
            String rendered = select.toString().trim();
            if (rendered.equals("*")) {
                for (Source source : sources.all) addStar(result, source);
            } else if (rendered.matches("(?i)`?[a-z_][a-z0-9_$]*`?\\s*\\.\\s*\\*")) {
                String alias = column(rendered.substring(0, rendered.indexOf('.')).trim());
                addStar(result, sources.alias(alias));
            } else {
                String expression = select.getExpression().toString();
                if (expression.length() > 5000) throw unsupported();
                result.add(new Item(expression));
            }
        }
        return result;
    }
    private static void addStar(List<Item> result, Source source) {
        if (source.fields == null) throw new IllegalArgumentException("无法展开缺少结构信息的 *");
        for (String field : source.fields) result.add(new Item(source.alias + "." + field));
    }
    private static List<String> cleanTargets(List<String> targetColumns) {
        if (targetColumns.isEmpty()) throw new IllegalArgumentException("缺少目标表列顺序");
        return targetColumns.stream().filter(Objects::nonNull).map(ColumnLineageSql::column).toList();
    }
    private static void add(List<Dependency> to, String expression, String role, Sources sources) {
        if (expression != null) to.addAll(dependencies(expression, role, sources));
    }
    private static List<Dependency> dependencies(String expression, String role, Sources sources) {
        if (expression == null) return List.of();
        if (expression.length() > 5000) throw unsupported();
        LinkedHashSet<Dependency> found = new LinkedHashSet<>();
        final boolean[] countAll = {false};
        try {
            Expression parsed = CCJSqlParserUtil.parseExpression(expression);
            parsed.accept(new ExpressionVisitorAdapter<Void>() {
                private int seen;
                @Override public <S> Void visit(Column field, S context) {
                    if (++seen > 256) throw unsupported();
                    String qualifier = field.getTable() == null || field.getTable().getName() == null || field.getTable().getName().isBlank()
                            ? null : column(field.getTable().getName());
                    String name = column(field.getColumnName());
                    Source source = qualifier == null ? sources.unqualified(name) : sources.alias(qualifier);
                    if (source.fields != null && !source.fields.contains(name))
                        throw new IllegalArgumentException("字段不在来源表结构中: " + name);
                    found.add(new Dependency(source.name, name, role, expression));
                    return null;
                }
                @Override public <S> Void visit(net.sf.jsqlparser.expression.Function function, S context) {
                    if ("count".equalsIgnoreCase(function.getName()) && function.getParameters() != null
                            && function.getParameters().size() == 1
                            && function.getParameters().getFirst() instanceof net.sf.jsqlparser.statement.select.AllColumns)
                        countAll[0] = true;
                    return super.visit(function, context);
                }
                @Override public <S> Void visit(net.sf.jsqlparser.expression.AnalyticExpression expression, S context) {
                    throw unsupported();
                }
            }, null);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw unsupported(); }
        // COUNT(*) has a row dependency even though it has no Column node.
        if (countAll[0]) {
            if (sources.all.size() != 1) throw new IllegalArgumentException("COUNT(*) 的行来源不唯一");
            found.add(new Dependency(sources.all.getFirst().name, "*", "行数", expression));
        }
        return List.copyOf(found);
    }
    private static boolean containsOutputAlias(Expression expression, Set<String> aliases) {
        final boolean[] found = {false};
        expression.accept(new ExpressionVisitorAdapter<Void>() {
            @Override public <S> Void visit(Column column, S context) {
                if ((column.getTable() == null || column.getTable().getName() == null || column.getTable().getName().isBlank())
                        && aliases.contains(ColumnLineageSql.column(column.getColumnName()))) found[0] = true;
                return null;
            }
        }, null);
        return found[0];
    }
    private static String text(Object value) { return value == null ? null : value.toString(); }
    private static String table(String value) {
        String plain = value.replace("`", "").toLowerCase(Locale.ROOT);
        if (!plain.matches("(warehouse\\.)?[a-z_][a-z0-9_]{0,63}")) throw unsupported();
        return plain.startsWith("warehouse.") ? plain.substring(10) : plain;
    }
    private static String column(String value) {
        String plain = value.replace("`", "").trim().toLowerCase(Locale.ROOT);
        if (!plain.matches("[a-z_][a-z0-9_$]{0,63}")) throw new IllegalArgumentException("无效列名");
        return plain;
    }
    private static IllegalArgumentException unsupported() { return new IllegalArgumentException("仅支持简单 INSERT INTO ... SELECT，不能推断该 SQL 的列级血缘"); }
    private record Item(String expression) {}
    private record Dependency(String table, String column, String role, String expression) {}
    private record Source(String name, String alias, List<String> fields) {}
    private record Sources(List<Source> all) {
        Source alias(String alias) {
            return all.stream().filter(s -> s.alias.equals(alias)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("未知表别名: " + alias));
        }
        Source unqualified(String field) {
            if (all.size() == 1) return all.getFirst(); // explicit field is enough evidence for a sole source
            List<Source> matches = all.stream().filter(s -> s.fields != null && s.fields.contains(field)).toList();
            if (all.stream().anyMatch(s -> s.fields == null) || matches.size() != 1)
                throw new IllegalArgumentException("无法唯一确定字段来源: " + field);
            return matches.getFirst();
        }
    }
}
