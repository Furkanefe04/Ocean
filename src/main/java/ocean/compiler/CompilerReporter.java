package ocean.compiler;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
/**
 * Ocean derleyicisi için teşhis ve hata raporlama sistemi.
 * Hata, uyarı ve bilgilendirme mesajlarını kaynak dosya konumu, satır, sütun ve ANSI renkleriyle biçimlendirir.
 */
public class CompilerReporter {
    public enum Level { ERROR, WARNING, INFO }

    public record Message(Level level, String file, int line, int column, String code, String text, String context) {

        @NotNull
        @Override
            public String toString() {
                String color = level == Level.ERROR ? "\u001B[31m" : (level == Level.WARNING ? "\u001B[33m" : "\u001B[34m");
                String reset = "\u001B[0m";

            StringBuilder sb = new StringBuilder();
                sb.append(color).append("[").append(level);
                if (code != null && !code.isEmpty()) {
                    sb.append(" ").append(code);
                }
                sb.append("]").append(reset).append(" ");
                if (file != null) sb.append(file).append(":");
                sb.append(line).append(":").append(column);
                if (context != null) sb.append(" (").append(context).append(")");
                sb.append("\n  | ").append(text);
                return sb.toString();
            }

            public String toJson() {
                return String.format("{\"level\":\"%s\",\"code\":\"%s\",\"file\":\"%s\",\"line\":%d,\"column\":%d,\"text\":\"%s\",\"context\":\"%s\"}",
                        level, code != null ? code : "", jsonEscape(file != null ? file.replace("\\", "/") : ""), line, column, jsonEscape(text), jsonEscape(context));
            }
        }

    private static final List<Message> fallbackMessages = new CopyOnWriteArrayList<>();

    private static List<Message> getMessagesList() {
        CompilationSession session = CompilationSession.getActiveSession();
        if (session != null) {
            return session.messages;
        }
        return fallbackMessages;
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': escaped.append("\\\""); break;
                case '\\': escaped.append("\\\\"); break;
                case '\b': escaped.append("\\b"); break;
                case '\f': escaped.append("\\f"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                     if (c < 0x20) {
                         escaped.append(String.format("\\u%04x", (int) c));
                     } else {
                         escaped.append(c);
                     }
            }
        }
        return escaped.toString();
    }

    public static String determineErrorCode(String text, String context) {
        if (text == null) return null;
        String lower = text.toLowerCase();
        
        if (lower.contains("başlatılmamış olabilir") || lower.contains("uninitialized") || lower.contains("might not have been initialized")) {
            return "E0001";
        }
        if (lower.contains("yeniden atanamaz") || lower.contains("cannot be reassigned") || lower.contains("cannot assign a value to final") || lower.contains("cannot assign final") || lower.contains("reassigned")) {
            return "E0002";
        }
        if (lower.contains("tipi") && (lower.contains("atanamaz") || lower.contains("uyumsuz")) ||
            lower.contains("incompatible types") || lower.contains("autocloseable") || lower.contains("lock expression must be a reference type") || lower.contains("lock ifadesi referans tipinde")) {
            return "E0003";
        }
        if (lower.contains("döngüsel kalıtım") || lower.contains("circular dependency") || lower.contains("circular inheritance") || lower.contains("cyclic dependency") || lower.contains("cyclic inheritance")) {
            return "E0004";
        }
        if (lower.contains("değer döndürmüyor") || lower.contains("tüm yollardan") || lower.contains("değer döndürmelidir") || lower.contains("missing return") || lower.contains("must return a value") || lower.contains("döndürülmez") || lower.contains("döndürülemez")) {
            return "E0005";
        }
        if (lower.contains("yakalanmamış checked exception") || lower.contains("checked exception") || lower.contains("unreported exception")) {
            return "E0006";
        }
        if (lower.contains("nullable değer üzerinde") || lower.contains("nullable") || lower.contains("dereference of null")) {
            return "E0007";
        }
        if (lower.contains("statik bağlamdan") || lower.contains("static context") || lower.contains("static olmayan") || lower.contains("non-static")) {
            return "E0008";
        }
        if (lower.contains("tanımsız tanımlayıcı") || lower.contains("tanımsız değişken") || lower.contains("tanımsız") || lower.contains("cannot find symbol") || lower.contains("undefined") || lower.contains("not found")) {
            return "E0009";
        }
        if (lower.contains("@override") || lower.contains("ezilirken") || lower.contains("override") ||
            lower.contains("soyut") || lower.contains("abstract") || lower.contains("gerçekleştirmelidir") || lower.contains("must implement")) {
            return "E0010";
        }
        if (lower.contains("syntax error") || "parser".equalsIgnoreCase(context)) {
            return "E0011";
        }
        if (lower.contains("sınıf adı") && lower.contains("dosyada tanımlanmalıdır") || lower.contains("class name mismatch") || lower.contains("should be declared in a file named")) {
            return "E0012";
        }
        if (lower.contains("zaten tanımlı") || lower.contains("duplicate") || lower.contains("already defined")) {
            return "E0013";
        }
        if (lower.contains("erişilemez") || lower.contains("private") || lower.contains("protected") || lower.contains("paket-özel") || lower.contains("has private access") || lower.contains("has protected access") || lower.contains("cannot be accessed")) {
            return "E0014";
        }
        return null;
    }

    public static void report(Level level, String file, int line, int column, String text, String context) {
        report(level, file, line, column, null, text, context);
    }

    public static void report(Level level, String file, int line, int column, String code, String text, String context) {
        String finalCode = (code != null) ? code : determineErrorCode(text, context);
        Message msg = new Message(level, file, line, column, finalCode, text, context);
        List<Message> list = getMessagesList();
        for (Message existing : list) {
            if (existing.level == level &&
                existing.line == line &&
                existing.column == column &&
                Objects.equals(existing.file, file) &&
                Objects.equals(existing.text, text)) {
                return;
            }
        }
        list.add(msg);
        if (list != fallbackMessages) {
            fallbackMessages.add(msg);
        }
    }

    public static void error(String file, int line, int column, String text, String context) {
        report(Level.ERROR, file, line, column, text, context);
    }

    public static void error(String file, int line, int column, String code, String text, String context) {
        report(Level.ERROR, file, line, column, code, text, context);
    }

    public static void warning(String file, int line, int column, String text, String context) {
        report(Level.WARNING, file, line, column, text, context);
    }

    public static void warning(String file, int line, int column, String code, String text, String context) {
        report(Level.WARNING, file, line, column, code, text, context);
    }

    public static void info(String file, int line, int column, String text, String context) {
        report(Level.INFO, file, line, column, text, context);
    }

    public static boolean hasErrors() {
        return getMessagesList().stream().anyMatch(m -> m.level() == Level.ERROR);
    }

    public static List<Message> getMessages() {
        return new ArrayList<>(getMessagesList());
    }

    public static void clear() {
        fallbackMessages.clear();
        CompilationSession session = CompilationSession.getActiveSession();
        if (session != null) {
            session.messages.clear();
        }
    }

    public static void printSummary() {
        List<Message> list = getMessagesList();
        if (list.isEmpty()) return;

        System.err.println("\n--- Ocean Compilation Report ---");
        list.forEach(System.err::println);

        long errors   = list.stream().filter(m -> m.level == Level.ERROR).count();
        long warnings = list.stream().filter(m -> m.level == Level.WARNING).count();
        long infos    = list.stream().filter(m -> m.level == Level.INFO).count();

        StringBuilder summary = new StringBuilder("\nSummary: ")
                .append(errors).append(" error(s), ")
                .append(warnings).append(" warning(s)");
        if (infos > 0) summary.append(", ").append(infos).append(" info(s)");
        System.err.println(summary);
        System.err.println("--------------------------------");
    }

    public static void printJson() {
        List<Message> list = getMessagesList();
        System.out.println("[");
        for (int i = 0; i < list.size(); i++) {
            System.out.print("  " + list.get(i).toJson());
            if (i < list.size() - 1) System.out.println(",");
            else System.out.println();
        }
        System.out.println("]");
    }
}
