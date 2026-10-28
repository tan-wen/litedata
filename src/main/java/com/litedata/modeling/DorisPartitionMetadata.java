package com.litedata.modeling;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Reads the single-column partition grammar without treating quoted values as SQL tokens. */
final class DorisPartitionMetadata {
    private DorisPartitionMetadata() { }

    static List<DorisPartitioning.Rule> parse(String ddl, String type) {
        var header = Pattern.compile("(?im)^\\s*PARTITION\\s+BY\\s+(?:RANGE|LIST)\\s*\\([^)]*\\)\\s*\\(").matcher(ddl);
        if (!header.find()) return List.of();
        var end = Pattern.compile("(?im)^\\s*DISTRIBUTED\\s+BY\\b").matcher(ddl);
        int limit = end.find(header.end()) ? end.start() : ddl.length();
        try {
            Reader reader = new Reader(ddl.substring(header.end(), limit));
            var rules = new ArrayList<DorisPartitioning.Rule>();
            while (!reader.take(")")) {
                reader.expect("PARTITION");
                String name = reader.next().toLowerCase(Locale.ROOT);
                reader.expect("VALUES");
                if ("MANUAL_LIST".equals(type)) {
                    reader.expect("IN"); reader.expect("(");
                    var values = new ArrayList<String>();
                    do {
                        boolean tuple = reader.take("(");
                        values.add(reader.next());
                        if (tuple) reader.expect(")");
                    } while (reader.take(","));
                    reader.expect(")");
                    rules.add(new DorisPartitioning.Rule(name, "", "", List.copyOf(values)));
                } else if (reader.take("LESS")) {
                    reader.expect("THAN"); reader.expect("("); String upper = reader.next(); reader.expect(")");
                    rules.add(new DorisPartitioning.Rule(name, "", upper, List.of()));
                } else {
                    reader.expect("["); reader.expect("("); String lower = reader.next(); reader.expect(")");
                    reader.expect(","); reader.expect("("); String upper = reader.next(); reader.expect(")"); reader.expect(")");
                    rules.add(new DorisPartitioning.Rule(name, lower, upper, List.of()));
                }
                if (!reader.take(",")) { reader.expect(")"); break; }
            }
            if (!reader.finished()) return List.of();
            return List.copyOf(rules);
        } catch (IllegalArgumentException exception) {
            return List.of(); // Unsupported metadata must produce a mismatch, never a false match.
        }
    }

    private record Token(String text, boolean quoted) { }
    private static final class Reader {
        private final List<Token> tokens = new ArrayList<>();
        private int index;

        Reader(String source) {
            int cursor = 0;
            while (cursor < source.length()) {
                char ch = source.charAt(cursor++);
                if (Character.isWhitespace(ch)) continue;
                if (ch == '\'' || ch == '"' || ch == '`') {
                    var value = new StringBuilder(); boolean closed = false;
                    while (cursor < source.length()) {
                        char part = source.charAt(cursor++);
                        if (part == '\\') {
                            if (cursor >= source.length()) throw new IllegalArgumentException();
                            value.append(source.charAt(cursor++));
                        } else if (part == ch) {
                            if (cursor < source.length() && source.charAt(cursor) == ch) { value.append(ch); cursor++; }
                            else { closed = true; break; }
                        } else value.append(part);
                    }
                    if (!closed) throw new IllegalArgumentException();
                    tokens.add(new Token(value.toString(), true));
                } else if ("(),[]".indexOf(ch) >= 0) tokens.add(new Token(Character.toString(ch), false));
                else {
                    int start = cursor - 1;
                    while (cursor < source.length() && !Character.isWhitespace(source.charAt(cursor)) && "(),[]".indexOf(source.charAt(cursor)) < 0) cursor++;
                    tokens.add(new Token(source.substring(start, cursor), false));
                }
            }
        }
        String next() { if (index >= tokens.size()) throw new IllegalArgumentException(); return tokens.get(index++).text(); }
        boolean take(String value) {
            if (index < tokens.size() && !tokens.get(index).quoted() && tokens.get(index).text().equalsIgnoreCase(value)) { index++; return true; }
            return false;
        }
        void expect(String value) { if (!take(value)) throw new IllegalArgumentException(); }
        boolean finished() { return index == tokens.size(); }
    }
}
