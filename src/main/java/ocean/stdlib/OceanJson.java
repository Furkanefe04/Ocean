package ocean.stdlib;

import java.math.BigDecimal;
import java.util.Map;
/**
 * Hafif ve hızlı JSON ayrıştırma (parse) ve nesne serileştirme (stringify) sağlayan standart kütüphane sınıfı.
 */
public class OceanJson {
    // 1. Serialization (stringify)
    public static String stringify(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String) {
            return "\"" + escape((String) obj) + "\"";
        }
        if (obj instanceof Number || obj instanceof Boolean) {
            return obj.toString();
        }
        if (obj instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escape(String.valueOf(entry.getKey()))).append("\":");
                sb.append(stringify(entry.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        if (obj instanceof Iterable<?> iter) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : iter) {
                if (!first) sb.append(",");
                first = false;
                sb.append(stringify(item));
            }
            sb.append("]");
            return sb.toString();
        }
        if (obj.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(obj);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < len; i++) {
                if (i > 0) sb.append(",");
                sb.append(stringify(java.lang.reflect.Array.get(obj, i)));
            }
            sb.append("]");
            return sb.toString();
        }
        // Fallback for custom objects
        return "\"" + escape(obj.toString()) + "\"";
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < ' ') {
                        String t = "000" + Integer.toHexString(ch);
                        sb.append("\\u").append(t.substring(t.length() - 4));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }

    // 2. Deserialization (parse)
    public static Object parse(String json) {
        if (json == null) return null;
        json = json.trim();
        if (json.isEmpty()) return null;
        return new Parser(json).parse();
    }

    private static class Parser {
        private final String src;
        private int pos = 0;

        Parser(String src) { this.src = src; }

        private char peek() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
            return pos < src.length() ? src.charAt(pos) : '\0';
        }

        private char next() {
            char c = peek();
            if (pos < src.length()) pos++;
            return c;
        }

        Object parse() {
            char c = peek();
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') {
                consume("null");
                return null;
            }
            if (Character.isDigit(c) || c == '-') return parseNumber();
            throw new RuntimeException("Unexpected character in JSON: " + c + " at pos " + pos);
        }

        private OceanMap<String, Object> parseObject() {
            consume('{');
            OceanMap<String, Object> map = new OceanMap<>();
            if (peek() == '}') {
                consume('}');
                return map;
            }
            while (true) {
                char c = peek();
                if (c != '"') throw new RuntimeException("Expected string key in object at pos " + pos);
                String key = parseString();
                if (next() != ':') throw new RuntimeException("Expected ':' in object at pos " + pos);
                Object val = parse();
                map.put(key, val);
                char nextChar = peek();
                if (nextChar == ',') {
                    consume(',');
                } else if (nextChar == '}') {
                    consume('}');
                    break;
                } else {
                    throw new RuntimeException("Expected ',' or '}' in object at pos " + pos);
                }
            }
            return map;
        }

        private OceanList<Object> parseArray() {
            consume('[');
            OceanList<Object> list = new OceanList<>();
            if (peek() == ']') {
                consume(']');
                return list;
            }
            while (true) {
                list.add(parse());
                char nextChar = peek();
                if (nextChar == ',') {
                    consume(',');
                } else if (nextChar == ']') {
                    consume(']');
                    break;
                } else {
                    throw new RuntimeException("Expected ',' or ']' in array at pos " + pos);
                }
            }
            return list;
        }

        private String parseString() {
            consume('"');
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= src.length()) throw new RuntimeException("Unexpected EOF in string escape");
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > src.length()) throw new RuntimeException("Invalid unicode escape");
                            String hex = src.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                            break;
                        default: sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new RuntimeException("Unterminated string");
        }

        private Boolean parseBoolean() {
            if (src.startsWith("true", pos)) {
                consume("true");
                return Boolean.TRUE;
            } else if (src.startsWith("false", pos)) {
                consume("false");
                return Boolean.FALSE;
            }
            throw new RuntimeException("Expected boolean at pos " + pos);
        }

        private Number parseNumber() {
            int start = pos;
            if (src.charAt(pos) == '-') pos++;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            String numStr = src.substring(start, pos);
            if (numStr.contains(".")) {
                return Double.parseDouble(numStr);
            }
            try {
                long val = Long.parseLong(numStr);
                if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
                    return (int) val;
                }
                return val;
            } catch (NumberFormatException e) {
                return new BigDecimal(numStr);
            }
        }

        private void consume(char expected) {
            char actual = next();
            if (actual != expected) {
                throw new RuntimeException("Expected '" + expected + "' but got '" + actual + "'");
            }
        }

        private void consume(String expected) {
            for (int i = 0; i < expected.length(); i++) {
                consume(expected.charAt(i));
            }
        }
    }
}
