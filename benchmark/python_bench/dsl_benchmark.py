import time
import sys

# Increase recursion depth for fib(38)
sys.setrecursionlimit(2000)

def fib(n):
    if n <= 1:
        return n
    return fib(n - 1) + fib(n - 2)

def run_benchmark():
    start = time.time()
    result = fib(38)
    end = time.time()
    print(f"Fibonacci(38) Result: {result}")
    print(f"Python DSL Suresi: {int((end - start) * 1000)}ms")

if __name__ == "__main__":
    run_benchmark()
