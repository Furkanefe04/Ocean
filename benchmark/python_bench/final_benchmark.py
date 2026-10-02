import time

class Base:
    def increment(self, n):
        return n + 1

class Sub(Base):
    def increment(self, n):
        return n + 2

def fib(n):
    if n <= 1: return n
    return fib(n - 1) + fib(n - 2)

def run_benchmark():
    print("--- Python Final Benchmark Suite ---")

    # 1. Turbo Loop (100M)
    print("\n[Test 1: Turbo Loop 100M]")
    start1 = time.time()
    sum_val = 0
    for i in range(1, 100000001):
        sum_val += 1
    end1 = time.time()
    print(f"Result: {sum_val}")
    print(f"Süre: {int((end1 - start1) * 1000)}ms")

    # 2. OOP Dispatch (3M)
    print("\n[Test 2: OOP Dynamic Dispatch 3M]")
    start2 = time.time()
    total = 0
    for i in range(3000000):
        s = Sub()
        total += s.increment(1)
    end2 = time.time()
    print(f"Result: {total}")
    print(f"Süre: {int((end2 - start2) * 1000)}ms")

    # 3. Logic (Fib 38)
    print("\n[Test 3: Recursive Fibonacci 38]")
    start3 = time.time()
    res_fib = fib(38)
    end3 = time.time()
    print(f"Result: {res_fib}")
    print(f"Süre: {int((end3 - start3) * 1000)}ms")

    print("\n--- Python Benchmark Suite Tamamlandı ---")

if __name__ == "__main__":
    run_benchmark()
