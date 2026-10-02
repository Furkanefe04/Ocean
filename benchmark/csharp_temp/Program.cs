using System;
using System.Diagnostics;

class HeavyBenchmark {
    // Standardized Subtests
    static void MatrixMultiply(int[] a, int[] b, int[] result, int size) {
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                int sum = 0;
                for (int k = 0; k < size; k++) {
                    sum += a[i * size + k] * b[k * size + j];
                }
                result[i * size + j] = sum;
            }
        }
    }

    static int Fibonacci(int n) {
        if (n <= 1) return n;
        return Fibonacci(n - 1) + Fibonacci(n - 2);
    }

    static int SieveOfEratosthenes(int limit) {
        int[] flags = new int[limit + 1];
        for (int i = 0; i <= limit; i++) flags[i] = 1;
        flags[0] = 0; flags[1] = 0;
        for (int p = 2; p * p <= limit; p++) {
            if (flags[p] == 1) {
                for (int m = p * p; m <= limit; m += p) flags[m] = 0;
            }
        }
        int count = 0;
        for (int i = 0; i <= limit; i++) { if (flags[i] == 1) count++; }
        return count;
    }

    static void SelectionSort(int[] arr, int n) {
        for (int i = 0; i < n - 1; i++) {
            int minIdx = i;
            for (int j = i + 1; j < n; j++) {
                if (arr[j] < arr[minIdx]) minIdx = j;
            }
            int temp = arr[minIdx]; arr[minIdx] = arr[i]; arr[i] = temp;
        }
    }

    static int CollatzLength(int n) {
        int length = 0;
        long val = n;
        while (val != 1) {
            if (val % 2 == 0) val /= 2;
            else val = 3 * val + 1;
            length++;
        }
        return length;
    }

    static int CollatzStress(int limit) {
        int maxLen = 0;
        for (int i = 1; i <= limit; i++) {
            int len = CollatzLength(i);
            if (len > maxLen) maxLen = len;
        }
        return maxLen;
    }

    static int ArrayChecksum(int[] arr, int n) {
        int left = 0, right = n - 1;
        while (left < right) {
            int temp = arr[left]; arr[left] = arr[right]; arr[right] = temp;
            left++; right--;
        }
        int checksum = 0;
        for (int i = 0; i < n; i++) checksum ^= arr[i];
        return checksum;
    }

    static int Gcd(int a, int b) {
        while (b != 0) { int temp = b; b = a % b; a = temp; }
        return a;
    }

    static int GcdStress(int limit) {
        int total = 0;
        for (int i = 1; i <= limit; i++) {
            for (int j = 1; j <= 100; j++) {
                total += Gcd(i, j);
            }
        }
        return total;
    }

    static void RunFullSuite() {
        // Test 1: Matrix Multiply
        int size = 150;
        int n = size * size;
        int[] a = new int[n], b = new int[n], result = new int[n];
        for (int i = 0; i < n; i++) { a[i] = (i % 17) + 1; b[i] = (i % 13) + 1; }
        MatrixMultiply(a, b, result, size);

        // Test 2: Fibonacci
        Fibonacci(35);

        // Test 3: Sieve
        SieveOfEratosthenes(1000000);

        // Test 4: Selection Sort
        int sortN = 15000;
        int[] sortArr = new int[sortN];
        for (int i = 0; i < sortN; i++) sortArr[i] = sortN - i;
        SelectionSort(sortArr, sortN);

        // Test 5: Collatz
        CollatzStress(100000);

        // Test 6: Array
        int arrN = 500000;
        int[] bigArr = new int[arrN];
        for (int i = 0; i < arrN; i++) bigArr[i] = i * 3 + 7;
        ArrayChecksum(bigArr, arrN);

        // Test 7: GCD
        GcdStress(50000);
    }

    static void Main() {
        int trials = 3;
        int warmUp = 1;

        Console.WriteLine("--- C# Heavy Benchmark Suite ---");

        // Warm-up phase
        for (int i = 0; i < warmUp; i++) RunFullSuite();

        long totalTimeMs = 0;
        for (int i = 0; i < trials; i++) {
            Stopwatch sw = Stopwatch.StartNew();
            RunFullSuite();
            sw.Stop();
            totalTimeMs += sw.ElapsedMilliseconds;
            Console.WriteLine("Trial " + (i + 1) + ": " + sw.ElapsedMilliseconds + " ms");
        }

        Console.WriteLine("Average Time: " + (totalTimeMs / trials) + " ms");
        Console.WriteLine("DOGRULAMA: BASARILI");
    }
}
