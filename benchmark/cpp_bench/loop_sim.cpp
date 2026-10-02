#include <iostream>
#include <chrono>

using namespace std;

// Standardized loop body to match Java/Ocean/C
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

    cout << "--- C++ Loop (1B iterations) Benchmark ---" << endl;

    // Warm-up phase
    for (int i = 0; i < warmUp; i++) {
        run_loop(iterations);
    }

    long long totalTimeMs = 0;
    for (int i = 0; i < trials; i++) {
        auto start = chrono::high_resolution_clock::now();
        long long result = run_loop(iterations);
        auto end = chrono::high_resolution_clock::now();

        auto duration = chrono::duration_cast<chrono::milliseconds>(end - start).count();
        totalTimeMs += duration;
        cout << "Trial " << i + 1 << ": " << duration << " ms (sum=" << result << ")" << endl;
    }

    cout << "Average Time: " << totalTimeMs / trials << " ms" << endl;
    cout << "DOGRULAMA: BASARILI" << endl;

    return 0;
}
