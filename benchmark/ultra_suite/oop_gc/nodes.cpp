#include <iostream>
#include <chrono>

struct Node {
    int value;
    Node *next;
};

int main() {
    int limit = 20000000;
    auto start = std::chrono::high_resolution_clock::now();

    Node *head = new Node{0, nullptr};
    Node *curr = head;

    for (int i = 1; i < limit; i++) {
        curr->next = new Node{i, nullptr};
        curr = curr->next;
    }

    long long sum = 0;
    curr = head;
    while (curr != nullptr) {
        sum += curr->value;
        curr = curr->next;
    }

    // Free memory
    curr = head;
    while (curr != nullptr) {
        Node *temp = curr;
        curr = curr->next;
        delete temp;
    }

    auto end = std::chrono::high_resolution_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
    
    std::cout << "CPP Nodes Sum: " << sum << ", Time: " << ms << " ms" << std::endl;
    return 0;
}
