import time

def fib(n):
    if n <= 1: return n
    return fib(n - 1) + fib(n - 2)

print("--- Python Recursive Fibonacci(38) Benchmark Baslatiliyor ---")
start_time = time.time()

result = fib(38)

end_time = time.time()
print(f"Sonuc (Fib 38): {result}")
print(f"Python Calisma Suresi: {int((end_time - start_time) * 1000)}ms")
print("--- Python Benchmark Tamamlandi ---")
