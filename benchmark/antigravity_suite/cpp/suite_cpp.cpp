#include <iostream>
#include <vector>
#include <chrono>
#include <cmath>
#include <cstring>

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

class BaseEntity {
public:
    virtual void speak() = 0;
    virtual ~BaseEntity() {}
};

class Entity : public BaseEntity {
public:
    int val = 0;
    void speak() override { val++; }
};

volatile long long g_dummy_cpp = 0;

int main() {
    using clock = std::chrono::high_resolution_clock;

    // 1. FIBONACCI
    auto t0 = clock::now();
    long long fsum = 0;
    for(int i = 0; i < 50000; i++) {
        fsum += fib_pure_iterative(100);
    }
    g_dummy_cpp += fsum;
    double fib_ms = std::chrono::duration<double, std::milli>(clock::now() - t0).count();

    // 2. CONTIGUOUS 1D MATRIX MULTIPLICATION (500x500 flat vector<double>)
    int N = 500;
    std::vector<double> A(N * N, 1.0);
    std::vector<double> B(N * N, 2.0);
    std::vector<double> C(N * N, 0.0);
    
    t0 = clock::now();
    for(int i = 0; i < N; i++) {
        for(int k = 0; k < N; k++) {
            double a_ik = A[i * N + k];
            for(int j = 0; j < N; j++) {
                C[i * N + j] += a_ik * B[k * N + j];
            }
        }
    }
    g_dummy_cpp += static_cast<long long>(C[0]);
    double matrix_ms = std::chrono::duration<double, std::milli>(clock::now() - t0).count();

    // 3. PRIME SIEVE (10M elements)
    int limit = 10000000;
    std::vector<bool> isPrime(limit + 1, true);
    t0 = clock::now();
    for (int p = 2; p * p <= limit; p++) {
        if (isPrime[p]) {
            for (int i = p * p; i <= limit; i += p)
                isPrime[i] = false;
        }
    }
    double sieve_ms = std::chrono::duration<double, std::milli>(clock::now() - t0).count();

    // 4. PURE VIRTUAL DISPATCH LATENCY (Pre-allocated persistent instance)
    Entity e;
    BaseEntity* ptr = &e;
    long iterations = 100000000; // 100M calls
    t0 = clock::now();
    for(long i = 0; i < iterations; i++) {
        ptr->speak();
    }
    g_dummy_cpp += e.val;
    double oop_ms = std::chrono::duration<double, std::milli>(clock::now() - t0).count();
    double dispatch_ns = (oop_ms * 1e6) / static_cast<double>(iterations);

    std::cout << "DYNAMIC_BENCH_RESULTS\n";
    std::cout << "FIB_MS:" << fib_ms << "\n";
    std::cout << "MATRIX_MS:" << matrix_ms << "\n";
    std::cout << "SIEVE_MS:" << sieve_ms << "\n";
    std::cout << "OOP_DISPATCH_NS:" << dispatch_ns << "\n";
    std::cout << "OOP_OVERHEAD_MS:" << oop_ms << "\n";

    return 0;
}
