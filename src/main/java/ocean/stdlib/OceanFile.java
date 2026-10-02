package ocean.stdlib;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
/**
 * Dosya okuma, yazma, kopyalama ve dizin işlemlerini kolaylaştıran standart G/Ç (I/O) yardımcı sınıfı.
 */
public final class OceanFile {
    private OceanFile() {
    }

    public static void write(String path, String content) {
        try {
            Files.writeString(Path.of(path), content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String read(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean delete(String path) {
        try {
            return Files.deleteIfExists(Path.of(path));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static OceanList<String> readLines(String path) {
        try {
            OceanList<String> out = new OceanList<>();
            out.addAll(Files.readAllLines(Path.of(path), StandardCharsets.UTF_8));
            return out;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void writeLines(String path, List<String> lines) {
        try {
            Files.write(Path.of(path), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean exists(String path) {
        return Files.exists(Path.of(path));
    }

    public static void append(String path, String text) {
        try {
            Files.writeString(Path.of(path), text == null ? "" : text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static OceanList<String> listFiles(String dir) {
        try {
            OceanList<String> out = new OceanList<>();
            try (var stream = Files.list(Path.of(dir))) {
                stream.forEach(p -> out.add(p.toString().replace('\\', '/')));
            }
            return out;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isDirectory(String path) {
        return Files.isDirectory(Path.of(path));
    }

    public static void copy(String from, String to) {
        try {
            Files.copy(Path.of(from), Path.of(to), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void move(String from, String to) {
        try {
            Files.move(Path.of(from), Path.of(to), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static long size(String path) {
        try {
            return Files.size(Path.of(path));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void createDirectories(String dir) {
        try {
            Files.createDirectories(Path.of(dir));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void createFile(String path) {
        try {
            Files.createFile(Path.of(path));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] readBytes(String path) {
        try {
            return Files.readAllBytes(Path.of(path));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void writeBytes(String path, byte[] bytes) {
        try {
            Files.write(Path.of(path), bytes == null ? new byte[0] : bytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String getName(String path) {
        if (path == null) return "";
        Path p = Path.of(path).getFileName();
        return p != null ? p.toString() : "";
    }

    public static String getExtension(String path) {
        if (path == null) return "";
        String name = getName(path);
        int dot = name.lastIndexOf('.');
        return (dot >= 0 && dot < name.length() - 1) ? name.substring(dot + 1) : "";
    }

    public static String getParent(String path) {
        if (path == null) return "";
        Path p = Path.of(path).getParent();
        return p != null ? p.toString().replace('\\', '/') : "";
    }

    public static boolean isRegularFile(String path) {
        return path != null && Files.isRegularFile(Path.of(path));
    }

    public static boolean isReadable(String path) {
        return path != null && Files.isReadable(Path.of(path));
    }

    public static boolean isWritable(String path) {
        return path != null && Files.isWritable(Path.of(path));
    }

    public static boolean isExecutable(String path) {
        return path != null && Files.isExecutable(Path.of(path));
    }
}
