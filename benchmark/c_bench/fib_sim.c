#include <stdio.h>
#include <time.h>

int fib(int n) {
    if (n <= 1) return n;
    return fib(n - 1) + fib(n - 2);
}

int main() {
    clock_t start = clock();
    int result = fib(38);
    clock_t end = clock();
    int dur = (int)((end - start) * 1000 / CLOCKS_PER_SEC);
    printf("==============================================\n");
    printf("  C FIBONACCI(38) BENCHMARK\n");
    printf("  Sure: %d ms\n", dur);
    printf("  Hesap: %d\n", result);
    printf("==============================================\n");
    return 0;
}
