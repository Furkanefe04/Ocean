import time

def fib(n):
    if n <= 1:
        return n
    return fib(n-1) + fib(n-2)

start = time.time()
result = fib(38)
dur = int((time.time() - start) * 1000)

print("==============================================")
print("  PYTHON FIBONACCI(38) BENCHMARK")
print("  Sure: {} ms".format(dur))
print("  Hesap: {}".format(result))
print("==============================================")
