public class JavaBench10StringBuilder {
    public static void main(String[] args) {
        int n = 100_000;
        long start = System.nanoTime();
        long total = 0;
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder();
            sb.append(i);
            sb.append(42);
            sb.append(i + 1);
            sb.append(99);
            sb.append(i * 2);
            total += sb.length();
        }
        long end = System.nanoTime();
        System.out.println("total_len=" + total + " | time_ms=" + ((end - start) / 1_000_000));
    }
}
