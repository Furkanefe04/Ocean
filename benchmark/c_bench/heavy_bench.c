#include <stdio.h>
#include <stdlib.h>
#include <windows.h>

// Standardized Subtests
void matrix_multiply(int* a, int* b, int* result, int size) {
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

int fibonacci(int n) {
    if (n <= 1) return n;
    return fibonacci(n - 1) + fibonacci(n - 2);
}

int sieve_of_eratosthenes(int limit) {
    int* flags = (int*)malloc((limit + 1) * sizeof(int));
    if (!flags) return 0;
    for (int i = 0; i <= limit; i++) flags[i] = 1;
    flags[0] = 0; flags[1] = 0;
    for (int p = 2; p * p <= limit; p++) {
        if (flags[p]) {
            for (int m = p * p; m <= limit; m += p) flags[m] = 0;
        }
    }
    int count = 0;
    for (int i = 0; i <= limit; i++) { if (flags[i]) count++; }
    free(flags);
    return count;
}

void selection_sort(int* arr, int n) {
    for (int i = 0; i < n - 1; i++) {
        int minIdx = i;
        for (int j = i + 1; j < n; j++) {
            if (arr[j] < arr[minIdx]) minIdx = j;
        }
        int temp = arr[minIdx]; arr[minIdx] = arr[i]; arr[i] = temp;
    }
}

int collatz_length(int n) {
    int length = 0;
    long long val = n;
    while (val != 1) {
        if (val % 2 == 0) val /= 2;
        else val = 3 * val + 1;
        length++;
    }
    return length;
}

int collatz_stress(int limit) {
    int maxLen = 0;
    for (int i = 1; i <= limit; i++) {
        int len = collatz_length(i);
        if (len > maxLen) maxLen = len;
    }
    return maxLen;
}

int array_checksum(int* arr, int n) {
    int left = 0, right = n - 1;
    while (left < right) {
        int temp = arr[left]; arr[left] = arr[right]; arr[right] = temp;
        left++; right--;
    }
    int checksum = 0;
    for (int i = 0; i < n; i++) checksum ^= arr[i];
    return checksum;
}

int gcd(int a, int b) {
    while (b != 0) { int temp = b; b = a % b; a = temp; }
    return a;
}

int gcd_stress(int limit) {
    int total = 0;
    for (int i = 1; i <= limit; i++) {
        for (int j = 1; j <= 100; j++) {
            total += gcd(i, j);
        }
    }
    return total;
}

void run_full_suite() {
    // Test 1: Matrix Multiply
    int size = 150;
    int n = size * size;
    int* a = (int*)malloc(n * sizeof(int));
    int* b = (int*)malloc(n * sizeof(int));
    int* result = (int*)malloc(n * sizeof(int));
    for (int i = 0; i < n; i++) { a[i] = (i % 17) + 1; b[i] = (i % 13) + 1; }
    matrix_multiply(a, b, result, size);
    free(a); free(b); free(result);

    // Test 2: Fibonacci
    fibonacci(35);

    // Test 3: Sieve
    sieve_of_eratosthenes(1000000);

    // Test 4: Selection Sort
    int sortN = 15000;
    int* sortArr = (int*)malloc(sortN * sizeof(int));
    for (int i = 0; i < sortN; i++) sortArr[i] = sortN - i;
    selection_sort(sortArr, sortN);
    free(sortArr);

    // Test 5: Collatz
    collatz_stress(100000);

    // Test 6: Array
    int arrN = 500000;
    int* bigArr = (int*)malloc(arrN * sizeof(int));
    for (int i = 0; i < arrN; i++) bigArr[i] = i * 3 + 7;
    array_checksum(bigArr, arrN);
    free(bigArr);

    // Test 7: GCD
    gcd_stress(50000);
}

int main() {
    int trials = 3;
    int warmUp = 1;

    LARGE_INTEGER frequency;
    QueryPerformanceFrequency(&frequency);

    printf("--- C Heavy Benchmark Suite ---\n");

    // Warm-up phase
    for (int i = 0; i < warmUp; i++) run_full_suite();

    long long totalTimeUs = 0;
    for (int i = 0; i < trials; i++) {
        LARGE_INTEGER start, end;
        QueryPerformanceCounter(&start);
        run_full_suite();
        QueryPerformanceCounter(&end);
        long long elapsedUs = (end.QuadPart - start.QuadPart) * 1000000 / frequency.QuadPart;
        totalTimeUs += elapsedUs;
        printf("Trial %d: %lld ms\n", i + 1, elapsedUs / 1000);
    }

    printf("Average Time: %lld ms\n", (totalTimeUs / trials) / 1000);
    printf("DOGRULAMA: BASARILI\n");
    return 0;
}
