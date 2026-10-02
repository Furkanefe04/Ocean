#include <stdio.h>
#include <stdlib.h>
#include <time.h>

int main() {
    int size = 100000000;
    int *arr = (int *)malloc(size * sizeof(int));
    if (arr == NULL) return 1;

    clock_t start = clock();
    long long sum = 0;
    for (int i = 0; i < size; i++) {
        arr[i] = i * 3 + 1;
        sum += arr[i];
    }
    clock_t end = clock();
    
    double ms = ((double)(end - start) / CLOCKS_PER_SEC) * 1000;
    printf("C Sum: %lld, Time: %.1f ms\n", sum, ms);
    
    free(arr);
    return 0;
}
