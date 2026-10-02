#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <string.h>
#include <stdbool.h>

#ifdef _WIN32
#include <windows.h>
static double get_time_ns() {
    LARGE_INTEGER freq, counter;
    QueryPerformanceFrequency(&freq);
    QueryPerformanceCounter(&counter);
    return (double)counter.QuadPart * 1e9 / (double)freq.QuadPart;
}
#else
static double get_time_ns() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec * 1e9 + (double)ts.tv_nsec;
}
#endif

// 1. PURE ITERATIVE FIBONACCI
long long fib_pure_iterative(int n) {
    if (n <= 1) return n;
    long long a = 0, b = 1, c = 0;
    for (int i = 2; i <= n; i++) {
        c = a + b;
        a = b;
        b = c;
    }
    return b;
}

volatile long long g_dummy = 0;

typedef struct Entity Entity;
struct Entity {
    void (*speak)(Entity*);
    int val;
};
void entity_speak(Entity* e) { e->val += 1; }

void test_all() {
    // 1. FIBONACCI
    double t0 = get_time_ns();
    long long fsum = 0;
    for(int i = 0; i < 50000; i++) {
        fsum += fib_pure_iterative(100);
    }
    g_dummy += fsum;
    double fib_ms = (get_time_ns() - t0) / 1e6;

    // 2. CONTIGUOUS 1D MATRIX MULTIPLICATION (500x500 flat double[N*N])
    int N = 500;
    double *A = (double*)malloc(N * N * sizeof(double));
    double *B = (double*)malloc(N * N * sizeof(double));
    double *C = (double*)malloc(N * N * sizeof(double));
    for(int i = 0; i < N * N; i++) { A[i] = 1.0; B[i] = 2.0; C[i] = 0.0; }
    
    t0 = get_time_ns();
    for(int i = 0; i < N; i++) {
        for(int k = 0; k < N; k++) {
            double a_ik = A[i * N + k];
            for(int j = 0; j < N; j++) {
                C[i * N + j] += a_ik * B[k * N + j];
            }
        }
    }
    g_dummy += (long long)C[0];
    double matrix_ms = (get_time_ns() - t0) / 1e6;

    // 3. PRIME SIEVE (10M elements)
    int limit = 10000000;
    bool *isPrime = (bool*)malloc((limit + 1) * sizeof(bool));
    memset(isPrime, true, (limit + 1) * sizeof(bool));
    t0 = get_time_ns();
    for (int p = 2; p * p <= limit; p++) {
        if (isPrime[p]) {
            for (int i = p * p; i <= limit; i += p)
                isPrime[i] = false;
        }
    }
    double sieve_ms = (get_time_ns() - t0) / 1e6;

    // 4. PURE VIRTUAL DISPATCH LATENCY (Pre-allocated persistent object)
    Entity e;
    e.speak = entity_speak;
    e.val = 0;
    long iterations = 100000000; // 100M calls
    t0 = get_time_ns();
    for(long i = 0; i < iterations; i++) {
        e.speak(&e);
    }
    g_dummy += e.val;
    double oop_ms = (get_time_ns() - t0) / 1e6;
    double dispatch_ns = (oop_ms * 1e6) / (double)iterations;

    free(A); free(B); free(C); free(isPrime);

    printf("DYNAMIC_BENCH_RESULTS\n");
    printf("FIB_MS:%.2f\n", fib_ms);
    printf("MATRIX_MS:%.2f\n", matrix_ms);
    printf("SIEVE_MS:%.2f\n", sieve_ms);
    printf("OOP_DISPATCH_NS:%.3f\n", dispatch_ns);
    printf("OOP_OVERHEAD_MS:%.2f\n", oop_ms);
}

int main(int argc, char** argv) {
    test_all();
    return 0;
}
