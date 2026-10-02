#include <iostream>
#include <chrono>
#include <cstring>
#ifdef _WIN32
#include <windows.h>
#include <psapi.h>
#endif

void counting_sort(int* arr, int n, int maxVal) {
    int* count = new int[maxVal + 1]();
    int* output = new int[n];
    for (int i = 0; i < n; i++) count[arr[i]]++;
    for (int i = 1; i <= maxVal; i++) count[i] += count[i - 1];
    for (int j = n - 1; j >= 0; j--) { int v = arr[j]; count[v]--; output[count[v]] = v; }
    std::memcpy(arr, output, n * sizeof(int));
    delete[] count; delete[] output;
}

long get_mem_kb() {
#ifdef _WIN32
    PROCESS_MEMORY_COUNTERS pmc;
    GetProcessMemoryInfo(GetCurrentProcess(), &pmc, sizeof(pmc));
    return (long)(pmc.WorkingSetSize / 1024);
#else
    return 0;
#endif
}

int main() {
    int size = 1000000, maxVal = 10000;
    int* arr = new int[size];
    for (int i = 0; i < size; i++) arr[i] = ((i * 7 + 13) * 31) % (maxVal + 1);
    long memBefore = get_mem_kb();
    auto t1 = std::chrono::high_resolution_clock::now();
    counting_sort(arr, size, maxVal);
    auto t2 = std::chrono::high_resolution_clock::now();
    long memAfter = get_mem_kb();
    bool sorted = true;
    for (int i = 0; i < size - 1; i++) if (arr[i] > arr[i+1]) sorted = false;
    int chk = 0; for (int i = 0; i < size; i++) chk ^= arr[i];
    std::cout << "C++ CountingSort | Size=" << size << " | Sure="
              << std::chrono::duration_cast<std::chrono::milliseconds>(t2-t1).count()
              << "ms | Sorted=" << sorted << " | Checksum=" << chk
              << " | Mem=" << (memAfter - memBefore) << "KB" << std::endl;
    delete[] arr;
    return 0;
}
