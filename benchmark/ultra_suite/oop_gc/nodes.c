#include <stdio.h>
#include <stdlib.h>
#include <time.h>

typedef struct Node {
    int value;
    struct Node *next;
} Node;

int main() {
    int limit = 20000000;
    clock_t start = clock();

    Node *head = (Node *)malloc(sizeof(Node));
    head->value = 0;
    head->next = NULL;
    Node *curr = head;

    for (int i = 1; i < limit; i++) {
        Node *newNode = (Node *)malloc(sizeof(Node));
        newNode->value = i;
        newNode->next = NULL;
        curr->next = newNode;
        curr = newNode;
    }

    long long sum = 0;
    curr = head;
    while (curr != NULL) {
        sum += curr->value;
        curr = curr->next;
    }

    // Free memory
    curr = head;
    while (curr != NULL) {
        Node *temp = curr;
        curr = curr->next;
        free(temp);
    }

    clock_t end = clock();
    double ms = ((double)(end - start) / CLOCKS_PER_SEC) * 1000;
    printf("C Nodes Sum: %lld, Time: %.1f ms\n", sum, ms);

    return 0;
}
