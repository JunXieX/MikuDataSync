package com.junxiex.mikudatasync.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal YAML subset parser: nested maps by indentation, scalars, booleans, numbers and
 * single-line empty collections. This is intentionally not a general purpose YAML parser;
 * it exists so the Velocity plugin needs no third-party dependency.
 */
public final class MiniYaml {

    private MiniYaml() {
    }

    public static Map<String, Object> parse(String text) throws ConfigException {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Map<String, Object>> stack = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        stack.add(root);
        indents.add(-1);

        List<String> lines = text.lines().toList();
        for (int index = 0; index < lines.size(); index++) {
            String rawLine = lines.get(index);
            int lineNumber = index + 1;
            if (rawLine.isBlank()) {
                continue;
            }
            int indent = 0;
            while (indent < rawLine.length() && rawLine.charAt(indent) == ' ') {
                indent++;
            }
            if (indent < rawLine.length() && rawLine.charAt(indent) == '\t') {
                throw new ConfigException("line " + lineNumber + ": tabs are not supported");
            }
            String content = stripComment(rawLine.substring(indent)).trim();
            if (content.isEmpty()) {
                continue;
            }
            int colon = findColon(content);
            if (colon < 0) {
                throw new ConfigException("line " + lineNumber + ": expected 'key: value'");
            }
            String key = content.substring(0, colon).trim();
            String value = content.substring(colon + 1).trim();
            if (key.isEmpty()) {
                throw new ConfigException("line " + lineNumber + ": empty key");
            }
            while (indent <= indents.get(indents.size() - 1)) {
                stack.remove(stack.size() - 1);
                indents.remove(indents.size() - 1);
            }
            Map<String, Object> current = stack.get(stack.size() - 1);
            if (value.isEmpty()) {
                Map<String, Object> child = new LinkedHashMap<>();
                current.put(key, child);
                stack.add(child);
                indents.add(indent);
            } else {
                current.put(key, parseScalar(value));
            }
        }
        return root;
    }

    private static String stripComment(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static int findColon(String content) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == ':' && !inSingle && !inDouble) {
                return i;
            }
        }
        return -1;
    }

    /** Wraps a scalar so callers can recover the exact text; the typed value is unchanged. */
    private static Object raw(String text, Object parsed) {
        return new RawScalar(text, parsed);
    }

    private static Object parseScalar(String value) throws ConfigException {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            String text = unescapeDoubleQuoted(value.substring(1, value.length() - 1));
            return raw(text, parseQuoted(text));
        }
        if (value.length() >= 2 && value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'') {
            String text = value.substring(1, value.length() - 1).replace("''", "'");
            return raw(text, parseQuoted(text));
        }
        if (value.equals("[]")) {
            return new ArrayList<>();
        }
        if (value.equals("{}")) {
            return new LinkedHashMap<String, Object>();
        }
        if (value.equalsIgnoreCase("true")) {
            return raw(value, Boolean.TRUE);
        }
        if (value.equalsIgnoreCase("false")) {
            return raw(value, Boolean.FALSE);
        }
        try {
            return raw(value, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
        }
        try {
            return raw(value, Double.parseDouble(value));
        } catch (NumberFormatException ignored) {
        }
        return raw(value, value);
    }

    /**
     * Quoted scalars keep their string type except for the two YAML booleans and plain integers,
     * which users routinely quote ("true", "2097152"). Without this a quoted number silently falls
     * back to the default value in getInt/getBoolean.
     */
    private static Object parseQuoted(String value) {
        if (value.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (value.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private static String unescapeDoubleQuoted(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                switch (next) {
                    case 'n' -> builder.append('\n');
                    case 't' -> builder.append('\t');
                    default -> builder.append(next);
                }
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    public static Object value(Map<String, Object> root, String path) {
        Object current = root;
        for (String part : path.split("[.]")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
            if (current == null) {
                return null;
            }
        }
        // Scalars are stored wrapped so the original text stays available; accessors see the value.
        return current instanceof RawScalar scalar ? scalar.parsed() : current;
    }

    /** Original text of a scalar, or null when the path is missing or not a scalar. */
    public static String rawText(Map<String, Object> root, String path) {
        Object current = root;
        for (String part : path.split("[.]")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
            if (current == null) {
                return null;
            }
        }
        return current instanceof RawScalar scalar ? scalar.text() : null;
    }

    public static String getString(Map<String, Object> root, String path, String defaultValue) {
        Object value = value(root, path);
        return value == null ? defaultValue : String.valueOf(value);
    }

    /**
     * Reads a scalar as the exact text that was written. parseScalar normalises numbers, so a numeric
     * secret like {@code 0012345} would come back as {@code 12345}; this accessor keeps the original.
     */
    public static String getRawString(Map<String, Object> root, String path, String defaultValue) {
        String text = rawText(root, path);
        if (text != null) {
            return text;
        }
        Object value = value(root, path);
        return value == null ? defaultValue : String.valueOf(value);
    }

    /** Original text of a scalar that was also parsed into a typed value. */
    public record RawScalar(String text, Object parsed) {
    }

    public static int getInt(Map<String, Object> root, String path, int defaultValue) {
        Object value = value(root, path);
        if (value instanceof Number number) {
            long asLong = number.longValue();
            // A value outside int range is a configuration mistake; report the default instead of
            // silently truncating it into a plausible but wrong number.
            return asLong < Integer.MIN_VALUE || asLong > Integer.MAX_VALUE ? defaultValue : (int) asLong;
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    public static long getLong(Map<String, Object> root, String path, long defaultValue) {
        Object value = value(root, path);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    public static boolean getBoolean(Map<String, Object> root, String path, boolean defaultValue) {
        Object value = value(root, path);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.equalsIgnoreCase("true")) {
                return true;
            }
            if (trimmed.equalsIgnoreCase("false")) {
                return false;
            }
        }
        return defaultValue;
    }
}
