import java.util.stream.IntStream;

public class JavaBench7Functional {
    public static void main(String[] args) {
        int n = 3_000_000;
        int[] arr = new int[n];
        for (int i = 0; i < n; i++) arr[i] = i;
        long start = System.nanoTime();
        long sum = IntStream.of(arr)
                            .filter(i -> i % 2 == 0)
                            .mapToLong(i -> (long) i * 3)
                            .sum();
        long end = System.nanoTime();
        System.out.println("pipeline_sum=" + sum + " | time_ms=" + ((end - start) / 1_000_000));
    }
}
