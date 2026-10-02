#include <stdio.h>
#include <time.h>

// Try to prevent inlining to measure raw call overhead
#ifdef _MSC_VER
__declspec(noinline) int add(int a, int b) { return a + b; }
#else
__attribute__((noinline)) int add(int a, int b) { return a + b; }
#endif

int main() {
    long long limit = 1000000000;
    clock_t start = clock();

    long long sum = 0;
    for (int i = 0; i < limit; i++) {
        sum += add(i, 1);
    }

    clock_t end = clock();
    double ms = ((double)(end - start) / CLOCKS_PER_SEC) * 1000;
    printf("C Calls Sum: %lld, Time: %.1f ms\n", sum, ms);

    return 0;
}
