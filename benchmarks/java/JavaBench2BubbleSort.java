public class JavaBench2BubbleSort {
    public static void main(String[] args) {
        int n = 6000;
        int[] arr = new int[n];
        for (int i = 0; i < n; i++) arr[i] = n - i;
        long start = System.nanoTime();
        for (int i = 0; i < n - 1; i++) {
            for (int j = 0; j < n - i - 1; j++) {
                if (arr[j] > arr[j + 1]) {
                    int tmp = arr[j]; arr[j] = arr[j + 1]; arr[j + 1] = tmp;
                }
            }
        }
        long end = System.nanoTime();
        System.out.println("sorted[0]=" + arr[0] + " sorted[last]=" + arr[n-1] + " | time_ms=" + ((end - start) / 1_000_000));
    }
}
