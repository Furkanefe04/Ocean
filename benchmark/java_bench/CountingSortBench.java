public class CountingSortBench {
    static void countingSort(int[] arr, int n, int maxVal) {
        int[] count = new int[maxVal + 1];
        int[] output = new int[n];
        for (int i = 0; i < n; i++) count[arr[i]]++;
        for (int i = 1; i <= maxVal; i++) count[i] += count[i - 1];
        for (int j = n - 1; j >= 0; j--) { int v = arr[j]; count[v]--; output[count[v]] = v; }
        System.arraycopy(output, 0, arr, 0, n);
    }
    public static void main(String[] args) {
        int size = 1000000, maxVal = 10000;
        int[] arr = new int[size];
        for (int i = 0; i < size; i++) arr[i] = ((i * 7 + 13) * 31) % (maxVal + 1);
        Runtime rt = Runtime.getRuntime(); rt.gc();
        long memBefore = rt.totalMemory() - rt.freeMemory();
        long t1 = System.currentTimeMillis();
        countingSort(arr, size, maxVal);
        long t2 = System.currentTimeMillis();
        long memAfter = rt.totalMemory() - rt.freeMemory();
        boolean sorted = true;
        for (int i = 0; i < size - 1; i++) if (arr[i] > arr[i+1]) sorted = false;
        int chk = 0; for (int v : arr) chk ^= v;
        System.out.println("JAVA CountingSort | Size=" + size + " | Sure=" + (t2-t1) + "ms | Sorted=" + sorted + " | Checksum=" + chk + " | Mem=" + ((memAfter-memBefore)/1024) + "KB");
    }
}
