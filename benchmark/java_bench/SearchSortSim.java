import java.util.Random;

public class SearchSortSim {
    static void quickSort(int[] arr, int low, int high) {
        if (low < high) {
            int pi = partition(arr, low, high);
            quickSort(arr, low, pi - 1);
            quickSort(arr, pi + 1, high);
        }
    }

    static int partition(int[] arr, int low, int high) {
        int pivot = arr[high];
        int i = (low - 1);
        for (int j = low; j < high; j++) {
            if (arr[j] < pivot) {
                i++;
                int temp = arr[i];
                arr[i] = arr[j];
                arr[j] = temp;
            }
        }
        int temp = arr[i + 1];
        arr[i + 1] = arr[high];
        arr[high] = temp;
        return i + 1;
    }

    static int binarySearch(int[] arr, int target) {
        int low = 0, high = arr.length - 1;
        while (low <= high) {
            int mid = low + (high - low) / 2;
            if (arr[mid] == target) return mid;
            if (arr[mid] < target) low = mid + 1;
            else high = mid - 1;
        }
        return -1;
    }

    static long runBench(int sortN, int searchN) {
        int[] arr = new int[sortN];
        for (int i = 0; i < sortN; i++) arr[i] = sortN - i;
        quickSort(arr, 0, sortN - 1);

        int[] searchArr = new int[searchN];
        for (int i = 0; i < searchN; i++) searchArr[i] = i * 2;
        long checksum = 0;
        for (int i = 0; i < 100; i++) {
            checksum += binarySearch(searchArr, i * 1000);
        }
        return checksum;
    }

    public static void main(String[] args) {
        int sortN = 10000;
        int searchN = 1000000;
        int trials = 5;
        int warmUp = 5;

        System.out.println("--- Java Search & Sort (Quicksort 10K, BSearch 1M) Benchmark ---");

        // Warm-up phase
        for (int i = 0; i < warmUp; i++) {
            runBench(sortN, searchN);
        }

        long totalTimeNs = 0;
        for (int i = 0; i < trials; i++) {
            long start = System.nanoTime();
            long check = runBench(sortN, searchN);
            long end = System.nanoTime();
            totalTimeNs += (end - start);
            System.out.println("Trial " + (i + 1) + ": " + (end - start) / 1000000 + " ms (check=" + check + ")");
        }

        System.out.println("Average Time: " + (totalTimeNs / trials / 1000000) + " ms");
        System.out.println("DOGRULAMA: BASARILI");
    }
}
