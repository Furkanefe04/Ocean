public class JavaBench6Exceptions {
    public static void main(String[] args) {
        int n = 100_000;
        int caught = 0;
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            try {
                throw new RuntimeException("bench");
            } catch (Exception e) {
                caught++;
            }
        }
        long end = System.nanoTime();
        System.out.println("caught=" + caught + " | time_ms=" + ((end - start) / 1_000_000));
    }
}
