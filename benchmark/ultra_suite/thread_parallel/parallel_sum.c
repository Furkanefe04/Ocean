#include <stdio.h>
#include <stdlib.h>
#include <windows.h>
#include <time.h>

#define NUM_THREADS 8
#define SIZE 100000000

typedef struct {
    int *arr;
    int start;
    int end;
    long long partialSum;
} ThreadData;

DWORD WINAPI sumChunk(LPVOID lpParam) {
    ThreadData *data = (ThreadData *)lpParam;
    long long s = 0;
    for (int i = data->start; i < data->end; i++) {
        s += data->arr[i];
    }
    data->partialSum = s;
    return 0;
}

int main() {
    int *arr = (int *)malloc(SIZE * sizeof(int));
    for (int i = 0; i < SIZE; i++) arr[i] = i;

    clock_t start = clock();

    HANDLE threads[NUM_THREADS];
    ThreadData data[NUM_THREADS];
    int chunkSize = SIZE / NUM_THREADS;

    for (int i = 0; i < NUM_THREADS; i++) {
        data[i].arr = arr;
        data[i].start = i * chunkSize;
        data[i].end = (i == NUM_THREADS - 1) ? SIZE : (i + 1) * chunkSize;
        threads[i] = CreateThread(NULL, 0, sumChunk, &data[i], 0, NULL);
    }

    WaitForMultipleObjects(NUM_THREADS, threads, TRUE, INFINITE);

    long long totalSum = 0;
    for (int i = 0; i < NUM_THREADS; i++) {
        totalSum += data[i].partialSum;
        CloseHandle(threads[i]);
    }

    clock_t end = clock();
    double ms = ((double)(end - start) / CLOCKS_PER_SEC) * 1000;
    printf("C Parallel Sum: %lld, Time: %.1f ms\n", totalSum, ms);

    free(arr);
    return 0;
}
