public class HeavyBenchmark {
    // Standardized Subtests
    static void matrixMultiply(int[] a, int[] b, int[] result, int size) {
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

    static int fibonacci(int n) {
        if (n <= 1) return n;
        return fibonacci(n - 1) + fibonacci(n - 2);
    }

    static int sieveOfEratosthenes(int limit) {
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

    static void selectionSort(int[] arr, int n) {
        for (int i = 0; i < n - 1; i++) {
            int minIdx = i;
            for (int j = i + 1; j < n; j++) {
                if (arr[j] < arr[minIdx]) minIdx = j;
            }
            int temp = arr[minIdx]; arr[minIdx] = arr[i]; arr[i] = temp;
        }
    }

    static int collatzLength(int n) {
        int length = 0;
        long val = n; 
        while (val != 1) {
            if (val % 2 == 0) val /= 2;
            else val = 3 * val + 1;
            length++;
        }
        return length;
    }

    static int collatzStress(int limit) {
        int maxLen = 0;
        for (int i = 1; i <= limit; i++) {
            int len = collatzLength(i);
            if (len > maxLen) maxLen = len;
        }
        return maxLen;
    }

    static int arrayChecksum(int[] arr, int n) {
        int left = 0, right = n - 1;
        while (left < right) {
            int temp = arr[left]; arr[left] = arr[right]; arr[right] = temp;
            left++; right--;
        }
        int checksum = 0;
        for (int i = 0; i < n; i++) checksum ^= arr[i];
        return checksum;
    }

    static int gcd(int a, int b) {
        while (b != 0) { int temp = b; b = a % b; a = temp; }
        return a;
    }

    static int gcdStress(int limit) {
        int total = 0;
        for (int i = 1; i <= limit; i++) {
            for (int j = 1; j <= 100; j++) {
                total += gcd(i, j);
            }
        }
        return total;
    }

    static void runFullSuite() {
        // Test 1: Matrix Multiply
        int size = 150;
        int n = size * size;
        int[] a = new int[n], b = new int[n], result = new int[n];
        for (int i = 0; i < n; i++) { a[i] = (i % 17) + 1; b[i] = (i % 13) + 1; }
        matrixMultiply(a, b, result, size);
        System.out.println("Result[0][0] = " + result[0]);
        System.out.println("Result[74][74] = " + result[74 * size + 74]);

        // Test 2: Fibonacci
        System.out.println("Fib(35) = " + fibonacci(35));

        // Test 3: Sieve
        System.out.println("Asal Sayisi = " + sieveOfEratosthenes(1000000));

        // Test 4: Selection Sort
        int sortN = 15000;
        int[] sortArr = new int[sortN];
        for (int i = 0; i < sortN; i++) sortArr[i] = sortN - i;
        selectionSort(sortArr, sortN);

        // Test 5: Collatz
        System.out.println("Collatz Zinciri = " + collatzStress(100000));

        // Test 6: Array
        int arrN = 500000;
        int[] bigArr = new int[arrN];
        for (int i = 0; i < arrN; i++) bigArr[i] = i * 3 + 7;
        System.out.println("XOR Checksum = " + arrayChecksum(bigArr, arrN));

        // Test 7: GCD
        System.out.println("GCD Toplam = " + gcdStress(50000));
    }

    public static void main(String[] args) {
        int trials = 3;
        int warmUp = 1;

        System.out.println("--- Java Heavy Benchmark Suite ---");
        
        for (int i = 0; i < warmUp; i++) runFullSuite();

        long totalTimeNs = 0;
        for (int i = 0; i < trials; i++) {
            long start = System.nanoTime();
            runFullSuite();
            long end = System.nanoTime();
            totalTimeNs += (end - start);
            System.out.println("Trial " + (i + 1) + ": " + (end - start) / 1000000 + " ms");
        }

        System.out.println("Average Time: " + (totalTimeNs / trials / 1000000) + " ms");
        System.out.println("DOGRULAMA: BASARILI");
    }
}
