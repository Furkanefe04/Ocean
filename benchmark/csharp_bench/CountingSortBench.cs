using System;
using System.Diagnostics;

class CountingSortBench {
    static void CountingSort(int[] arr, int n, int maxVal) {
        int[] count = new int[maxVal + 1];
        int[] output = new int[n];
        for (int i = 0; i < n; i++) count[arr[i]]++;
        for (int i = 1; i <= maxVal; i++) count[i] += count[i - 1];
        for (int j = n - 1; j >= 0; j--) { int v = arr[j]; count[v]--; output[count[v]] = v; }
        Array.Copy(output, arr, n);
    }
    static void Main() {
        int size = 1000000, maxVal = 10000;
        int[] arr = new int[size];
        for (int i = 0; i < size; i++) arr[i] = ((i * 7 + 13) * 31) % (maxVal + 1);
        long memBefore = GC.GetTotalMemory(true);
        Stopwatch sw = Stopwatch.StartNew();
        CountingSort(arr, size, maxVal);
        sw.Stop();
        long memAfter = GC.GetTotalMemory(false);
        bool sorted = true;
        for (int i = 0; i < size - 1; i++) if (arr[i] > arr[i+1]) sorted = false;
        int chk = 0; foreach (int v in arr) chk ^= v;
        Console.WriteLine("C# CountingSort | Size=" + size + " | Sure=" + sw.ElapsedMilliseconds + "ms | Sorted=" + sorted + " | Checksum=" + chk + " | Mem=" + ((memAfter-memBefore)/1024) + "KB");
    }
}
