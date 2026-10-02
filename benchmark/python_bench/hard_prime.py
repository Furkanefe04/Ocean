import time

def count_primes(max_val):
    count = 0
    for i in range(2, max_val + 1):
        is_prime = 1
        j = 2
        while j * j <= i:
            if i % j == 0:
                is_prime = 0
                break
            j += 1
        if is_prime == 1:
            count += 1
    return count

start = time.time()
count = count_primes(10000000)
dur = int((time.time() - start) * 1000)

print("==============================================")
print("  PYTHON HARD PRIME (10M) BENCHMARK")
print("  Sure: {} ms".format(dur))
print("  Hesap: {}".format(count))
print("==============================================")
