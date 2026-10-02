#include <iostream>
#include <vector>
#include <chrono>

int main() {
    int N = 10000000;
    int ROUNDS = 5;

    std::cout << "=== C++ std::vector<int> Benchmark (N=" << N << ", Rounds=" << ROUNDS << ") ===" << std::endl;

    auto totalInitStart = std::chrono::high_resolution_clock::now();
    std::vector<int> list;
    list.reserve(N);
    for (int i = 0; i < N; i++) {
        list.push_back(i % 1000);
    }
    auto initEnd = std::chrono::high_resolution_clock::now();
    long long initTimeMs = std::chrono::duration_cast<std::chrono::milliseconds>(initEnd - totalInitStart).count();
    std::cout << "List Initialization Time: " << initTimeMs << " ms" << std::endl;

    long long totalSum = 0;
    auto totalExecStart = std::chrono::high_resolution_clock::now();

    for (int r = 0; r < ROUNDS; r++) {
        auto roundStart = std::chrono::high_resolution_clock::now();

        // 1. Indexed updates
        for (int i = 0; i < N; i++) {
            int updated = (list[i] * 3 + 7) % 10000;
            list[i] = updated;
        }

        // 2. Foreach iteration sum
        long long sum = 0;
        for (int val : list) {
            sum += val;
        }
        totalSum += sum;

        auto roundEnd = std::chrono::high_resolution_clock::now();
        long long roundTimeMs = std::chrono::duration_cast<std::chrono::milliseconds>(roundEnd - roundStart).count();
        std::cout << "  Round " << (r + 1) << ": " << roundTimeMs << " ms (Checksum: " << sum << ")" << std::endl;
    }

    auto totalExecEnd = std::chrono::high_resolution_clock::now();
    long long totalExecTimeMs = std::chrono::duration_cast<std::chrono::milliseconds>(totalExecEnd - totalExecStart).count();
    std::cout << "----------------------------------------------" << std::endl;
    std::cout << "Total Execution Time: " << totalExecTimeMs << " ms (Avg: " << (totalExecTimeMs / ROUNDS) << " ms/round)" << std::endl;
    std::cout << "Final Checksum: " << totalSum << std::endl;

    return 0;
}