package ocean.compiler;

/**
 * Utility for V3 string operations (e.g. unescaping literal escape sequences).
 */
public final class StringHelper {

    private StringHelper() {}

    /**
     * Processes escape sequences (\n, \t, \r, \", \\, etc.) in string literals.
     */
    public static String unescapeString(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n' -> { sb.append('\n'); i++; }
                    case 't' -> { sb.append('\t'); i++; }
                    case 'r' -> { sb.append('\r'); i++; }
                    case 'b' -> { sb.append('\b'); i++; }
                    case 'f' -> { sb.append('\f'); i++; }
                    case '"' -> { sb.append('"'); i++; }
                    case '\'' -> { sb.append('\''); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    case 's' -> { sb.append(' '); i++; }
                    case '0', '1', '2', '3', '4', '5', '6', '7' -> {
                        int octalLen = 1;
                        if (next <= '3') {
                            if (i + 2 < s.length() && s.charAt(i + 2) >= '0' && s.charAt(i + 2) <= '7') {
                                octalLen = 2;
                                if (i + 3 < s.length() && s.charAt(i + 3) >= '0' && s.charAt(i + 3) <= '7') {
                                    octalLen = 3;
                                }
                            }
                        } else {
                            if (i + 2 < s.length() && s.charAt(i + 2) >= '0' && s.charAt(i + 2) <= '7') {
                                octalLen = 2;
                            }
                        }
                        String octStr = s.substring(i + 1, i + 1 + octalLen);
                        sb.append((char) Integer.parseInt(octStr, 8));
                        i += octalLen;
                    }
                    case 'u' -> {
                        int uPos = i + 1;
                        while (uPos < s.length() && s.charAt(uPos) == 'u') {
                            uPos++;
                        }
                        if (uPos + 4 <= s.length()) {
                            try {
                                String hex = s.substring(uPos, uPos + 4);
                                char unicode = (char) Integer.parseInt(hex, 16);
                                sb.append(unicode);
                                i = uPos + 3;
                            } catch (NumberFormatException e) {
                                sb.append(c);
                            }
                        } else {
                            sb.append(c);
                        }
                    }
                    default -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Strips common whitespace indentation from multiline text blocks in Ocean multiline strings.
     */
    public static String stripIndent(String s) {
        if (s == null || s.isEmpty()) return s;

        // 1. Normalize line breaks (\r\n and \r -> \n)
        String normalized = s.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);

        int startIdx = 0;
        // If first line is empty (e.g. immediately after opening triple quotes), skip it
        if (lines.length > 1 && lines[0].isEmpty()) {
            startIdx = 1;
        }

        // 2. Compute minimum common leading whitespace for all non-blank lines
        int minIndent = Integer.MAX_VALUE;
        for (int i = startIdx; i < lines.length; i++) {
            String line = lines[i];
            boolean isLast = (i == lines.length - 1);
            boolean isBlank = isBlank(line);

            if (isBlank) {
                // If the closing delimiter line has whitespace, it defines indentation threshold
                if (isLast && !line.isEmpty()) {
                    minIndent = Math.min(minIndent, countLeadingWhitespace(line));
                }
            } else {
                minIndent = Math.min(minIndent, countLeadingWhitespace(line));
            }
        }

        if (minIndent == Integer.MAX_VALUE) {
            minIndent = 0;
        }

        // 3. Build stripped output
        StringBuilder sb = new StringBuilder();
        int endIdx = lines.length;
        // If last line was just closing delimiter whitespace on its own line and became empty
        if (lines.length > 1 && isBlank(lines[lines.length - 1])) {
            endIdx = lines.length - 1;
        }

        for (int i = startIdx; i < endIdx; i++) {
            String line = lines[i];
            if (minIndent > 0 && line.length() >= minIndent) {
                sb.append(line.substring(minIndent));
            } else if (!isBlank(line)) {
                sb.append(line);
            }
            if (i < endIdx - 1) {
                sb.append('\n');
            }
        }

        return sb.toString();
    }

    /**
     * Processes escape sequences in text blocks, supporting line continuation (\ + newline) and \s.
     */
    public static String unescapeTextBlock(String s) {
        if (s == null || s.isEmpty()) return s;

        // 1. Line continuation: backslash followed by optional whitespace and newline
        StringBuilder sb = new StringBuilder(s.length());
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < len) {
                // Check if followed by spaces/tabs then \n or \r\n
                int j = i + 1;
                while (j < len && (s.charAt(j) == ' ' || s.charAt(j) == '\t')) {
                    j++;
                }
                if (j < len && s.charAt(j) == '\n') {
                    i = j; // skip backslash, whitespace and newline
                    continue;
                } else if (j + 1 < len && s.charAt(j) == '\r' && s.charAt(j + 1) == '\n') {
                    i = j + 1;
                    continue;
                }
            }
            sb.append(c);
        }

        // 2. Process standard escapes including \s
        return unescapeString(sb.toString());
    }

    private static boolean isBlank(String s) {
        if (s == null || s.isEmpty()) return true;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) return false;
        }
        return true;
    }

    private static int countLeadingWhitespace(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }
}
