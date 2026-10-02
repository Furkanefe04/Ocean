package ocean.stdlib;

import java.util.Arrays;
import java.util.regex.Pattern;
/**
 * Karakter dizisi (String) manipülasyonu, bölme, kırpma ve dönüştürme işlevleri sunan standart kütüphane sınıfı.
 */
public final class OceanString {
    private OceanString() {
    }

    public static OceanList<String> split(String value, String delimiter) {
        OceanList<String> out = new OceanList<>();
        if (value == null) {
            return out;
        }
        if (delimiter == null) {
            out.add(value);
            return out;
        }
        out.addAll(Arrays.asList(value.split(Pattern.quote(delimiter))));
        return out;
    }

    public static int length(String value) {
        return value == null ? 0 : value.length();
    }

    public static boolean contains(String value, String substring) {
        return value != null && substring != null && value.contains(substring);
    }

    public static boolean startsWith(String value, String prefix) {
        return value != null && prefix != null && value.startsWith(prefix);
    }

    public static boolean endsWith(String value, String suffix) {
        return value != null && suffix != null && value.endsWith(suffix);
    }

    public static String toUpperCase(String value) {
        return value == null ? null : value.toUpperCase();
    }

    public static String toLowerCase(String value) {
        return value == null ? null : value.toLowerCase();
    }

    public static String trim(String value) {
        return value == null ? null : value.trim();
    }

    public static String replace(String value, String oldVal, String newVal) {
        return value == null || oldVal == null || newVal == null ? value : value.replace(oldVal, newVal);
    }

    public static int indexOf(String value, String substring) {
        return value == null || substring == null ? -1 : value.indexOf(substring);
    }

    public static String substring(String value, int start, int end) {
        return value == null ? null : value.substring(start, end);
    }

    public static int toInt(String value) {
        return Integer.parseInt(value);
    }

    public static double toDouble(String value) {
        return Double.parseDouble(value);
    }

    public static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static String format(String template, Object... args) {
        return String.format(template, args);
    }

    public static String join(CharSequence delimiter, Iterable<? extends CharSequence> elements) {
        if (elements == null) return "";
        return String.join(delimiter != null ? delimiter : "", elements);
    }

    public static String repeat(String value, int count) {
        if (value == null) return null;
        return value.repeat(Math.max(0, count));
    }

    public static String strip(String value) {
        return value == null ? null : value.strip();
    }

    public static String stripLeading(String value) {
        return value == null ? null : value.stripLeading();
    }

    public static String stripTrailing(String value) {
        return value == null ? null : value.stripTrailing();
    }

    public static String reverse(String value) {
        if (value == null) return null;
        return new StringBuilder(value).reverse().toString();
    }
}
