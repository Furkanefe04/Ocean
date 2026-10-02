#include <stdio.h>
#include <stdlib.h>
#include <windows.h>

typedef struct {
    int a;
    int b;
} Node;

int main() {
    LARGE_INTEGER frequency, start, end;
    QueryPerformanceFrequency(&frequency);
    QueryPerformanceCounter(&start);
    
    long long sum = 0;
    int iterations = 50000000;
    for (int i = 1; i <= iterations; i++) {
        Node* n = (Node*)malloc(sizeof(Node));
        if (n) {
            n->a = i;
            n->b = i + 1;
            if (i == 49999999) {
                sum = (long long)n->a + n->b;
            }
            free(n);
        }
    }
    
    QueryPerformanceCounter(&end);
    long long durMs = (end.QuadPart - start.QuadPart) * 1000 / frequency.QuadPart;
    
    printf("==============================================\n");
    printf("  C OBJECT ALLOC (50M) BENCHMARK\n");
    printf("  Sure: %lld ms\n", durMs);
    printf("  Hesap: %lld\n", sum);
    printf("==============================================\n");
    
    return 0;
}
