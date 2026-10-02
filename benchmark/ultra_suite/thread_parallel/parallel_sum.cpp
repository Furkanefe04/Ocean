#include <iostream>
#include <vector>
#include <thread>
#include <numeric>
#include <chrono>

void sumChunk(const int* arr, int start, int end, long long& partialSum) {
    long long s = 0;
    for (int i = start; i < end; ++i) {
        s += arr[i];
    }
    partialSum = s;
}

int main() {
    const int SIZE = 100000000;
    std::vector<int> arr(SIZE);
    for (int i = 0; i < SIZE; ++i) arr[i] = i;

    int numThreads = 8;
    int chunkSize = SIZE / numThreads;
    std::vector<std::thread> threads;
    std::vector<long long> partialSums(numThreads, 0);

    auto start = std::chrono::high_resolution_clock::now();

    for (int i = 0; i < numThreads; ++i) {
        int startIdx = i * chunkSize;
        int endIdx = (i == numThreads - 1) ? SIZE : (i + 1) * chunkSize;
        threads.emplace_back(sumChunk, arr.data(), startIdx, endIdx, std::ref(partialSums[i]));
    }

    for (auto& t : threads) {
        t.join();
    }

    long long totalSum = 0;
    for (long long s : partialSums) {
        totalSum += s;
    }

    auto end = std::chrono::high_resolution_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();

    std::cout << "CPP Parallel Sum: " << totalSum << ", Time: " << ms << " ms" << std::endl;

    return 0;
}
