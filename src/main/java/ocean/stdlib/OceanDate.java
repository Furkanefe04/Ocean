package ocean.stdlib;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
/**
 * Tarih ve saat manipülasyonu, biçimlendirme ve sorgulama işlevleri sunan standart kütüphane sınıfı.
 */
public class OceanDate {
    public static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    public static String format(String pattern) {
        if (pattern == null) return now();
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern(pattern));
    }

    public static long epochMillis() {
        return System.currentTimeMillis();
    }

    public static String formatMillis(long millis, String pattern) {
        java.time.Instant instant = java.time.Instant.ofEpochMilli(millis);
        LocalDateTime dt = LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
        return dt.format(DateTimeFormatter.ofPattern(pattern));
    }

    public static String today() {
        return java.time.LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public static long epochSeconds() {
        return System.currentTimeMillis() / 1000L;
    }
}
