public class JavaBench5StringOps {
    public static void main(String[] args) {
        int n = 200_000;
        long start = System.nanoTime();
        long total = 0;
        for (int i = 0; i < n; i++) {
            String s = "ocean_bench_" + i + "_string_operation_test";
            total += s.length();
        }
        long end = System.nanoTime();
        System.out.println("total_chars=" + total + " | time_ms=" + ((end - start) / 1_000_000));
    }
}
