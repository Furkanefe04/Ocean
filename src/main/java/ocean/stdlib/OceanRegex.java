package ocean.stdlib;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * Düzenli ifade (regular expression) desen eşleme, arama ve değiştirme işlevleri sunan standart kütüphane sınıfı.
 */
public class OceanRegex {
    public static boolean matches(String regex, String input) {
        if (regex == null || input == null) return false;
        return Pattern.matches(regex, input);
    }

    public static String replaceAll(String input, String regex, String replacement) {
        if (input == null || regex == null || replacement == null) return input;
        return input.replaceAll(regex, replacement);
    }

    public static OceanList<String> findAll(String regex, String input) {
        OceanList<String> results = new OceanList<>();
        if (regex == null || input == null) return results;
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(input);
        while (matcher.find()) {
            results.add(matcher.group());
        }
        return results;
    }

    public static OceanList<OceanList<String>> findGroups(String regex, String input) {
        OceanList<OceanList<String>> results = new OceanList<>();
        if (regex == null || input == null) return results;
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(input);
        while (matcher.find()) {
            OceanList<String> groups = new OceanList<>();
            for (int i = 0; i <= matcher.groupCount(); i++) {
                groups.add(matcher.group(i));
            }
            results.add(groups);
        }
        return results;
    }

    public static String findFirst(String regex, String input) {
        if (regex == null || input == null) return null;
        Matcher matcher = Pattern.compile(regex).matcher(input);
        return matcher.find() ? matcher.group() : null;
    }

    public static String replaceFirst(String input, String regex, String replacement) {
        if (input == null || regex == null || replacement == null) return input;
        return input.replaceFirst(regex, replacement);
    }

    public static OceanList<String> split(String regex, String input) {
        OceanList<String> results = new OceanList<>();
        if (input == null || regex == null) return results;
        String[] tokens = input.split(regex);
        results.addAll(Arrays.asList(tokens));
        return results;
    }

    public static boolean contains(String regex, String input) {
        if (regex == null || input == null) return false;
        return Pattern.compile(regex).matcher(input).find();
    }
}
