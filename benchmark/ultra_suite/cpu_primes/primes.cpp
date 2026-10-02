#include <iostream>
#include <chrono>

int countPrimes(int limit) {
    int count = 0;
    for (int i = 2; i <= limit; i++) {
        bool isPrime = true;
        for (int j = 2; j * j <= i; j++) {
            if (i % j == 0) {
                isPrime = false;
                break;
            }
        }
        if (isPrime) count++;
    }
    return count;
}

int main() {
    int limit = 2000000;
    auto start = std::chrono::high_resolution_clock::now();
    int count = countPrimes(limit);
    auto end = std::chrono::high_resolution_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
    std::cout << "CPP Count: " << count << ", Time: " << ms << " ms" << std::endl;
    return 0;
}
