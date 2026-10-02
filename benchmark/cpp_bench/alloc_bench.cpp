#include <iostream>
#include <chrono>

using namespace std;

struct Node { int a; int b; };

int main() {
    auto start = chrono::high_resolution_clock::now();
    long long sum = 0;
    for (int i = 1; i <= 50000000; i++) {
        Node* n = new Node();
        n->a = i;
        n->b = i + 1;
        if (i == 49999999) { sum = n->a + n->b; }
        delete n;
    }
    auto end = chrono::high_resolution_clock::now();
    int dur = chrono::duration_cast<chrono::milliseconds>(end - start).count();
    cout << "==============================================" << endl;
    cout << "  C++ OBJECT ALLOC (50M) BENCHMARK" << endl;
    cout << "  Sure: " << dur << " ms" << endl;
    cout << "  Hesap: " << sum << endl;
    cout << "==============================================" << endl;
    return 0;
}
