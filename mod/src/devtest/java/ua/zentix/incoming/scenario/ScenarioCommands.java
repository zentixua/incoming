package ua.zentix.incoming.scenario;

import java.util.ArrayList;
import java.util.List;

/**
 * List of script commands {@code commands} ({@code incoming.commands}): items separated by “;”. The semicolon inside
 *  parentheses and quotes is part of the command, not a separator: in NBT it separates the type and elements of the array
 * ({@code UUID:[I;1,2,3,4]}), in a line it is just a sign. A double quote opens a string everywhere, a single quote only opens
 *  where SNBT is waiting for a value or key (after {@code { [ , :}): the apostrophe in the text ({@code say it's}) is just a sign.
 */
public final class ScenarioCommands {
    private ScenarioCommands() {}

    /** Items by ";" top level, no spaces around the edges and no empty ones. */
    public static List<String> split(String spec) {
        List<String> out = new ArrayList<>();
        StringBuilder item = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < spec.length(); i++) {
            char c = spec.charAt(i);
            if (quote != 0) {
                if (c == '\\' && i + 1 < spec.length()) {
                    item.append(c).append(spec.charAt(++i));
                    continue;
                }
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'' && opensValue(item)) {
                quote = c;
            } else if (c == '[' || c == '{' || c == '(') {
                depth++;
            } else if ((c == ']' || c == '}' || c == ')') && depth > 0) {
                depth--;
            } else if (c == ';' && depth == 0) {
                add(out, item);
                continue;
            }
            item.append(c);
        }
        add(out, item);
        return out;
    }

    /** The last non-blank character of the clause is the beginning of the value or key SNBT. */
    private static boolean opensValue(StringBuilder item) {
        for (int i = item.length() - 1; i >= 0; i--) {
            char c = item.charAt(i);
            if (!Character.isWhitespace(c)) return c == '{' || c == '[' || c == ',' || c == ':';
        }
        return false;
    }

    private static void add(List<String> out, StringBuilder item) {
        String s = item.toString().strip();
        if (!s.isEmpty()) out.add(s);
        item.setLength(0);
    }
}
