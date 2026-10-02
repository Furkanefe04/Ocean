#include <iostream>
#include <chrono>

#ifdef _MSC_VER
__declspec(noinline) int add(int a, int b) { return a + b; }
#else
__attribute__((noinline)) int add(int a, int b) { return a + b; }
#endif

int main() {
    long long limit = 1000000000;
    auto start = std::chrono::high_resolution_clock::now();

    long long sum = 0;
    for (int i = 0; i < limit; i++) {
        sum += add(i, 1);
    }

    auto end = std::chrono::high_resolution_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
    
    std::cout << "CPP Calls Sum: " << sum << ", Time: " << ms << " ms" << std::endl;
    return 0;
}
