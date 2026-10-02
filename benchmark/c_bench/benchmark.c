#include <stdio.h>
#include <time.h>

typedef int (*increment_func)(int);

struct Base {
    increment_func increment;
};

int sub_increment(int n) {
    return n + 1;
}

int fib(int n) {
    if (n <= 1) return n;
    return fib(n - 1) + fib(n - 2);
}

int main() {
    printf("--- C Final Benchmark Suite ---\n\n");

    // Test 1: Turbo Loop 100M
    clock_t start = clock();
    volatile long long total = 0;
    for (int i = 0; i <= 100000000; i++) {
        total = i;
    }
    clock_t end = clock();
    printf("[Test 1: Turbo Loop 100M]\nResult: %lld\nDuration: %ldms\n\n", total, (end - start) * 1000 / CLOCKS_PER_SEC);

    // Test 2: OOP Dynamic Dispatch (vtable simulation) 3M
    struct Base sub;
    sub.increment = sub_increment;
    start = clock();
    volatile long oop_total = 0;
    for (int i = 0; i < 3000000; i++) {
        oop_total += sub.increment(1);
    }
    end = clock();
    printf("[Test 2: OOP vtable Simulation 3M]\nResult: %ld\nDuration: %ldms\n\n", oop_total, (end - start) * 1000 / CLOCKS_PER_SEC);

    // Test 3: Recursive Fibonacci 38
    start = clock();
    int fib_res = fib(38);
    end = clock();
    printf("[Test 3: Recursive Fibonacci 38]\nResult: %d\nDuration: %ldms\n\n", fib_res, (end - start) * 1000 / CLOCKS_PER_SEC);

    return 0;
}
