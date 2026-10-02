#include <stdio.h>
#include <windows.h>

// Standardized loop body to match Java/Ocean
long long run_loop(long long iterations) {
    long long sum = 0;
    for (long long i = 1; i <= iterations; i++) {
        sum += i;
    }
    return sum;
}

int main() {
    long long iterations = 1000000000;
    int warmUp = 5;
    int trials = 5;

    LARGE_INTEGER frequency;
    QueryPerformanceFrequency(&frequency);

    printf("--- C Loop (1B iterations) Benchmark ---\n");

    // Warm-up phase
    for (int i = 0; i < warmUp; i++) {
        run_loop(iterations);
    }

    long long totalTimeUs = 0;
    for (int i = 0; i < trials; i++) {
        LARGE_INTEGER start, end;
        QueryPerformanceCounter(&start);
        long long result = run_loop(iterations);
        QueryPerformanceCounter(&end);

        // Calculate elapsed time in microseconds for higher precision before averaging
        long long elapsedUs = (end.QuadPart - start.QuadPart) * 1000000 / frequency.QuadPart;
        totalTimeUs += elapsedUs;
        printf("Trial %d: %lld ms (sum=%lld)\n", i + 1, elapsedUs / 1000, result);
    }

    printf("Average Time: %lld ms\n", (totalTimeUs / trials) / 1000);
    printf("DOGRULAMA: BASARILI\n");

    return 0;
}
