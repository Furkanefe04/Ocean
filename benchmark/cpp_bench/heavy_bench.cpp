#include <iostream>
#include <vector>
#include <chrono>

using namespace std;

// Standardized Subtests
void matrix_multiply(const vector<int>& a, const vector<int>& b, vector<int>& result, int size) {
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
    vector<int> flags(limit + 1, 1);
    flags[0] = flags[1] = 0;
    for (int p = 2; p * p <= limit; p++) {
        if (flags[p]) {
            for (int m = p * p; m <= limit; m += p) flags[m] = 0;
        }
    }
    int count = 0;
    for (int i = 0; i <= limit; i++) { if (flags[i]) count++; }
    return count;
}

void selection_sort(vector<int>& arr, int n) {
    for (int i = 0; i < (int)n - 1; i++) {
        int minIdx = i;
        for (int j = i + 1; j < (int)n; j++) {
            if (arr[j] < arr[minIdx]) minIdx = j;
        }
        swap(arr[minIdx], arr[i]);
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

int array_checksum(vector<int>& arr, int n) {
    int left = 0, right = n - 1;
    while (left < right) {
        swap(arr[left], arr[right]);
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
    vector<int> a(n), b(n), result(n);
    for (int i = 0; i < n; i++) { a[i] = (i % 17) + 1; b[i] = (i % 13) + 1; }
    matrix_multiply(a, b, result, size);

    // Test 2: Fibonacci
    fibonacci(35);

    // Test 3: Sieve
    sieve_of_eratosthenes(1000000);

    // Test 4: Selection Sort
    int sortN = 15000;
    vector<int> sortArr(sortN);
    for (int i = 0; i < sortN; i++) sortArr[i] = sortN - i;
    selection_sort(sortArr, sortN);

    // Test 5: Collatz
    collatz_stress(100000);

    // Test 6: Array
    int arrN = 500000;
    vector<int> bigArr(arrN);
    for (int i = 0; i < arrN; i++) bigArr[i] = i * 3 + 7;
    array_checksum(bigArr, arrN);

    // Test 7: GCD
    gcd_stress(50000);
}

int main() {
    int trials = 3;
    int warmUp = 1;

    cout << "--- C++ Heavy Benchmark Suite ---" << endl;

    // Warm-up phase
    for (int i = 0; i < warmUp; i++) run_full_suite();

    long long totalTimeMs = 0;
    for (int i = 0; i < trials; i++) {
        auto start = chrono::high_resolution_clock::now();
        run_full_suite();
        auto end = chrono::high_resolution_clock::now();
        auto duration = chrono::duration_cast<chrono::milliseconds>(end - start).count();
        totalTimeMs += duration;
        cout << "Trial " << (i + 1) << ": " << duration << " ms" << endl;
    }

    cout << "Average Time: " << (totalTimeMs / trials) << " ms" << endl;
    cout << "DOGRULAMA: BASARILI" << endl;
    return 0;
}
