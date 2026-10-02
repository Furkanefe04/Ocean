package ocean.stdlib;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
/**
 * Günün saati (LocalTime), zaman biçimlendirme ve saat sorgulama işlevleri sunan standart kütüphane sınıfı.
 */
public class OceanTime {
    public static String now() {
        return LocalTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME);
    }

    public static String format(String pattern) {
        if (pattern == null) return now();
        return LocalTime.now().format(DateTimeFormatter.ofPattern(pattern));
    }

    public static long nanoTime() {
        return System.nanoTime();
    }
}
