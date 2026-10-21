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

    private static Object parseScalar(String value) throws ConfigException {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return unescapeDoubleQuoted(value.substring(1, value.length() - 1));
        }
        if (value.length() >= 2 && value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'') {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        if (value.equals("[]")) {
            return new ArrayList<>();
        }
        if (value.equals("{}")) {
            return new LinkedHashMap<String, Object>();
        }
        if (value.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (value.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
        }
        return value;
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

    @SuppressWarnings("unchecked")
    public static Map<String, Object> section(Map<String, Object> root, String path) {
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
        return current instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
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
        return current;
    }

    public static String getString(Map<String, Object> root, String path, String defaultValue) {
        Object value = value(root, path);
        return value == null ? defaultValue : String.valueOf(value);
    }

    public static int getInt(Map<String, Object> root, String path, int defaultValue) {
        Object value = value(root, path);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    public static long getLong(Map<String, Object> root, String path, long defaultValue) {
        Object value = value(root, path);
        return value instanceof Number number ? number.longValue() : defaultValue;
    }

    public static boolean getBoolean(Map<String, Object> root, String path, boolean defaultValue) {
        Object value = value(root, path);
        return value instanceof Boolean bool ? bool : defaultValue;
    }

    public static List<?> getList(Map<String, Object> root, String path) {
        Object value = value(root, path);
        return value instanceof List<?> list ? list : List.of();
    }
}
