#include <iostream>
#include <chrono>

class Base {
public:
    virtual int increment(int n) = 0;
    virtual ~Base() {}
};

class Sub : public Base {
public:
    int increment(int n) override {
        return n + 1;
    }
};

int fib(int n) {
    if (n <= 1) return n;
    return fib(n - 1) + fib(n - 2);
}

int main() {
    std::cout << "--- C++ Final Benchmark Suite ---" << std::endl << std::endl;

    // Test 1: Turbo Loop 100M
    auto start = std::chrono::high_resolution_clock::now();
    volatile long long total = 0;
    for (long long i = 0; i <= 100000000; i++) {
        total = i;
    }
    auto end = std::chrono::high_resolution_clock::now();
    std::cout << "[Test 1: Turbo Loop 100M]\nResult: " << total << "\nDuration: " 
              << std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count() << "ms\n" << std::endl;

    // Test 2: OOP Dynamic Dispatch 3M
    Sub sub;
    Base* base = &sub;
    start = std::chrono::high_resolution_clock::now();
    volatile long oop_total = 0;
    for (int i = 0; i < 3000000; i++) {
        oop_total += base->increment(1);
    }
    end = std::chrono::high_resolution_clock::now();
    std::cout << "[Test 2: OOP Dynamic Dispatch 3M]\nResult: " << oop_total << "\nDuration: " 
              << std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count() << "ms\n" << std::endl;

    // Test 3: Recursive Fibonacci 38
    start = std::chrono::high_resolution_clock::now();
    int fib_res = fib(38);
    end = std::chrono::high_resolution_clock::now();
    std::cout << "[Test 3: Recursive Fibonacci 38]\nResult: " << fib_res << "\nDuration: " 
              << std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count() << "ms\n" << std::endl;

    return 0;
}
