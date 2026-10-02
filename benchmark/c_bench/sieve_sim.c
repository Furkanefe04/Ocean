#include <stdio.h>
#include <stdlib.h>
#include <time.h>

int countPrimes(int max) {
    int* isPrime = (int*)calloc(max, sizeof(int));
    isPrime[0] = 1; isPrime[1] = 1;
    int p = 2;
    while (p * p <= max) {
        if (isPrime[p] == 0) {
            int i = p * p;
            while (i < max) { isPrime[i] = 1; i += p; }
        }
        p++;
    }
    int count = 0;
    for (int i = 2; i < max; i++) { if (isPrime[i] == 0) count++; }
    free(isPrime);
    return count;
}

int main() {
    clock_t start = clock();
    int count = countPrimes(20000000);
    clock_t end = clock();
    int dur = (int)((end - start) * 1000 / CLOCKS_PER_SEC);
    printf("==============================================\n");
    printf("  C SIEVE(20M) BENCHMARK\n");
    printf("  Sure: %d ms\n", dur);
    printf("  Hesap: %d\n", count);
    printf("==============================================\n");
    return 0;
}
