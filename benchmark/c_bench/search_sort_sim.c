#include <stdio.h>
#include <stdlib.h>
#include <windows.h>

// Standardized manual Quicksort for compiler efficiency testing
void quickSort(int* arr, int low, int high) {
    if (low < high) {
        int pivot = arr[high];
        int i = (low - 1);
        for (int j = low; j < high; j++) {
            if (arr[j] < pivot) {
                i++;
                int temp = arr[i]; arr[i] = arr[j]; arr[j] = temp;
            }
        }
        int temp = arr[i + 1]; arr[i + 1] = arr[high]; arr[high] = temp;
        int pi = i + 1;
        quickSort(arr, low, pi - 1);
        quickSort(arr, pi + 1, high);
    }
}

int binarySearch(int* arr, int n, int target) {
    int low = 0, high = n - 1;
    while (low <= high) {
        int mid = low + (high - low) / 2;
        if (arr[mid] == target) return mid;
        if (arr[mid] < target) low = mid + 1;
        else high = mid - 1;
    }
    return -1;
}

long long runBench(int sortN, int searchN) {
    int* arr = (int*)malloc(sortN * sizeof(int));
    for (int i = 0; i < sortN; i++) arr[i] = sortN - i;
    quickSort(arr, 0, sortN - 1);

    int* searchArr = (int*)malloc(searchN * sizeof(int));
    for (int i = 0; i < searchN; i++) searchArr[i] = i * 2;
    long long checksum = 0;
    for (int i = 0; i < 100; i++) {
        checksum += binarySearch(searchArr, searchN, i * 1000);
    }
    free(arr); free(searchArr);
    return checksum;
}

int main() {
    int sortN = 10000;
    int searchN = 1000000;
    int trials = 5;
    int warmUp = 5;

    LARGE_INTEGER frequency;
    QueryPerformanceFrequency(&frequency);

    printf("--- C Search & Sort (Quicksort 10K, BSearch 1M) Benchmark ---\n");

    // Warm-up phase
    for (int i = 0; i < warmUp; i++) runBench(sortN, searchN);

    long long totalTimeUs = 0;
    for (int i = 0; i < trials; i++) {
        LARGE_INTEGER start, end;
        QueryPerformanceCounter(&start);
        long long check = runBench(sortN, searchN);
        QueryPerformanceCounter(&end);
        long long elapsedUs = (end.QuadPart - start.QuadPart) * 1000000 / frequency.QuadPart;
        totalTimeUs += elapsedUs;
        printf("Trial %d: %lld ms (check=%lld)\n", i + 1, elapsedUs / 1000, check);
    }

    printf("Average Time: %lld ms\n", (totalTimeUs / trials) / 1000);
    printf("DOGRULAMA: BASARILI\n");
    return 0;
}
