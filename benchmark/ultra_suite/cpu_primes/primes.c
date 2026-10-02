#include <stdio.h>
#include <time.h>

int countPrimes(int limit) {
    int count = 0;
    for (int i = 2; i <= limit; i++) {
        int isPrime = 1;
        for (int j = 2; j * j <= i; j++) {
            if (i % j == 0) {
                isPrime = 0;
                break;
            }
        }
        if (isPrime) count++;
    }
    return count;
}

int main() {
    int limit = 2000000;
    clock_t start = clock();
    int count = countPrimes(limit);
    clock_t end = clock();
    double ms = ((double)(end - start) / CLOCKS_PER_SEC) * 1000;
    printf("C Count: %d, Time: %.1f ms\n", count, ms);
    return 0;
}
