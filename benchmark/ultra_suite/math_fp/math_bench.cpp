#include <iostream>
#include <cmath>
#include <chrono>

int main() {
    int limit = 100000000;
    auto start = std::chrono::high_resolution_clock::now();

    double res = 0.0;
    for (int i = 0; i < limit; i++) {
        res += std::sin(i) * std::cos(i) + std::sqrt(i);
    }

    auto end = std::chrono::high_resolution_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
    
    std::cout << "CPP Math Res: " << res << ", Time: " << ms << " ms" << std::endl;
    return 0;
}
