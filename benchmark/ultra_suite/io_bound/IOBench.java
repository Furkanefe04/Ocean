import java.io.*;

public class IOBench {
    public static void main(String[] args) throws IOException {
        long totalSize = 1024L * 1024 * 1024; // 1GB
        int bufferSize = 64 * 1024; // 64KB
        byte[] buffer = new byte[bufferSize];
        String filename = "io_test_java.bin";

        long start = System.currentTimeMillis();

        // Write
        try (FileOutputStream out = new FileOutputStream(filename)) {
            for (long i = 0; i < totalSize; i += bufferSize) {
                out.write(buffer);
            }
        }

        // Read
        long totalRead = 0;
        try (FileInputStream in = new FileInputStream(filename)) {
            while (in.read(buffer) != -1) {
                totalRead += bufferSize;
            }
        }

        long end = System.currentTimeMillis();
        System.out.println("Java IO Total: " + totalRead + " bytes, Time: " + (end - start) + " ms");

        new File(filename).delete();
    }
}
