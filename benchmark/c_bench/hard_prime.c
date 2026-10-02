#include <stdio.h>
#include <time.h>

int countPrimes(int max) {
    int count = 0;
    for (int i = 2; i <= max; i++) {
        int isPrime = 1;
        for (int j = 2; j * j <= i; j++) {
            if (i % j == 0) {
                isPrime = 0;
                break;
            }
        }
        if (isPrime == 1) count++;
    }
    return count;
}

int main() {
    clock_t start = clock();
    int count = countPrimes(10000000);
    clock_t end = clock();
    int dur = (int)((end - start) * 1000 / CLOCKS_PER_SEC);
    printf("==============================================\n");
    printf("  C HARD PRIME (10M) BENCHMARK\n");
    printf("  Sure: %d ms\n", dur);
    printf("  Hesap: %d\n", count);
    printf("==============================================\n");
    return 0;
}
