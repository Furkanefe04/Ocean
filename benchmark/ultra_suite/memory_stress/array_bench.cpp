#include <iostream>
#include <vector>
#include <chrono>

int main() {
    int size = 100000000;
    std::vector<int> arr(size);

    auto start = std::chrono::high_resolution_clock::now();
    long long sum = 0;
    for (int i = 0; i < size; i++) {
        arr[i] = i * 3 + 1;
        sum += arr[i];
    }
    auto end = std::chrono::high_resolution_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
    
    std::cout << "CPP Sum: " << sum << ", Time: " << ms << " ms" << std::endl;
    return 0;
}
