#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#ifdef _WIN32
#include <windows.h>
#include <psapi.h>
#endif

void counting_sort(int* arr, int n, int maxVal) {
    int* count = (int*)calloc(maxVal + 1, sizeof(int));
    int* output = (int*)malloc(n * sizeof(int));
    for (int i = 0; i < n; i++) count[arr[i]]++;
    for (int i = 1; i <= maxVal; i++) count[i] += count[i - 1];
    for (int j = n - 1; j >= 0; j--) { int v = arr[j]; count[v]--; output[count[v]] = v; }
    memcpy(arr, output, n * sizeof(int));
    free(count); free(output);
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
    int* arr = (int*)malloc(size * sizeof(int));
    for (int i = 0; i < size; i++) arr[i] = ((i * 7 + 13) * 31) % (maxVal + 1);
    long memBefore = get_mem_kb();
    clock_t t1 = clock();
    counting_sort(arr, size, maxVal);
    clock_t t2 = clock();
    long memAfter = get_mem_kb();
    int sorted = 1;
    for (int i = 0; i < size - 1; i++) if (arr[i] > arr[i+1]) sorted = 0;
    int chk = 0; for (int i = 0; i < size; i++) chk ^= arr[i];
    printf("C CountingSort | Size=%d | Sure=%ldms | Sorted=%d | Checksum=%d | Mem=%ldKB\n",
           size, (t2-t1)*1000/CLOCKS_PER_SEC, sorted, chk, memAfter - memBefore);
    free(arr);
    return 0;
}
