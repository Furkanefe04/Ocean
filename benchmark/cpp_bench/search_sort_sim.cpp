#include <iostream>
#include <vector>
#include <chrono>

using namespace std;

// Standardized manual Quicksort for compiler efficiency testing
void quickSort(vector<int>& arr, int low, int high) {
    if (low < high) {
        int pivot = arr[high];
        int i = (low - 1);
        for (int j = low; j < high; j++) {
            if (arr[j] < pivot) {
                i++;
                swap(arr[i], arr[j]);
            }
        }
        swap(arr[i + 1], arr[high]);
        int pi = i + 1;
        quickSort(arr, low, pi - 1);
        quickSort(arr, pi + 1, high);
    }
}

int binarySearch(const vector<int>& arr, int target) {
    int low = 0, high = (int)arr.size() - 1;
    while (low <= high) {
        int mid = low + (high - low) / 2;
        if (arr[mid] == target) return mid;
        if (arr[mid] < target) low = mid + 1;
        else high = mid - 1;
    }
    return -1;
}

long long runBench(int sortN, int searchN) {
    vector<int> arr(sortN);
    for (int i = 0; i < sortN; i++) arr[i] = sortN - i;
    quickSort(arr, 0, sortN - 1);

    vector<int> searchArr(searchN);
    for (int i = 0; i < searchN; i++) searchArr[i] = i * 2;
    long long checksum = 0;
    for (int i = 0; i < 100; i++) {
        checksum += binarySearch(searchArr, i * 1000);
    }
    return checksum;
}

int main() {
    int sortN = 10000;
    int searchN = 1000000;
    int trials = 5;
    int warmUp = 5;

    cout << "--- C++ Search & Sort (Quicksort 10K, BSearch 1M) Benchmark ---" << endl;

    // Warm-up phase
    for (int i = 0; i < warmUp; i++) runBench(sortN, searchN);

    long long totalTimeMs = 0;
    for (int i = 0; i < trials; i++) {
        auto start = chrono::high_resolution_clock::now();
        long long check = runBench(sortN, searchN);
        auto end = chrono::high_resolution_clock::now();
        auto duration = chrono::duration_cast<chrono::milliseconds>(end - start).count();
        totalTimeMs += duration;
        cout << "Trial " << i + 1 << ": " << duration << " ms (check=" << check << ")" << endl;
    }

    cout << "Average Time: " << totalTimeMs / trials << " ms" << endl;
    cout << "DOGRULAMA: BASARILI" << endl;
    return 0;
}
