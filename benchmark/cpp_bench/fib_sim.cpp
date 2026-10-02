#include <iostream>
#include <chrono>

using namespace std;

int fib(int n) {
    if (n <= 1) return n;
    return fib(n - 1) + fib(n - 2);
}

int main() {
    auto start = chrono::high_resolution_clock::now();
    int result = fib(38);
    auto end = chrono::high_resolution_clock::now();
    int dur = chrono::duration_cast<chrono::milliseconds>(end - start).count();
    cout << "==============================================" << endl;
    cout << "  C++ FIBONACCI(38) BENCHMARK" << endl;
    cout << "  Sure: " << dur << " ms" << endl;
    cout << "  Hesap: " << result << endl;
    cout << "==============================================" << endl;
    return 0;
}
