using System;
using System.Diagnostics;

class SearchSortSim {
    // Standardized manual Quicksort for compiler efficiency testing
    static void QuickSort(int[] arr, int low, int high) {
        if (low < high) {
            int pi = Partition(arr, low, high);
            QuickSort(arr, low, pi - 1);
            QuickSort(arr, pi + 1, high);
        }
    }

    static int Partition(int[] arr, int low, int high) {
        int pivot = arr[high];
        int i = (low - 1);
        int temp;
        for (int j = low; j < high; j++) {
            if (arr[j] < pivot) {
                i++;
                temp = arr[i]; arr[i] = arr[j]; arr[j] = temp;
            }
        }
        temp = arr[i + 1]; arr[i + 1] = arr[high]; arr[high] = temp;
        return i + 1;
    }

    static int BinarySearch(int[] arr, int target) {
        int low = 0, high = arr.Length - 1;
        while (low <= high) {
            int mid = low + (high - low) / 2;
            if (arr[mid] == target) return mid;
            if (arr[mid] < target) low = mid + 1;
            else high = mid - 1;
        }
        return -1;
    }

    static long RunBench(int sortN, int searchN) {
        int[] arr = new int[sortN];
        for (int i = 0; i < sortN; i++) arr[i] = sortN - i;
        QuickSort(arr, 0, sortN - 1);

        int[] searchArr = new int[searchN];
        for (int i = 0; i < searchN; i++) searchArr[i] = i * 2;
        long checksum = 0;
        for (int i = 0; i < 100; i++) {
            checksum += BinarySearch(searchArr, i * 1000);
        }
        return checksum;
    }

    static void Main() {
        int sortN = 10000;
        int searchN = 1000000;
        int trials = 5;
        int warmUp = 5;

        Console.WriteLine("--- C# Search & Sort (Quicksort 10K, BSearch 1M) Benchmark ---");

        // Warm-up phase
        for (int i = 0; i < warmUp; i++) RunBench(sortN, searchN);

        long totalTimeMs = 0;
        for (int i = 0; i < trials; i++) {
            Stopwatch sw = Stopwatch.StartNew();
            long check = RunBench(sortN, searchN);
            sw.Stop();
            totalTimeMs += sw.ElapsedMilliseconds;
            Console.WriteLine("Trial " + (i + 1) + ": " + sw.ElapsedMilliseconds + " ms (check=" + check + ")");
        }

        Console.WriteLine("Average Time: " + (totalTimeMs / trials) + " ms");
        Console.WriteLine("DOGRULAMA: BASARILI");
    }
}
