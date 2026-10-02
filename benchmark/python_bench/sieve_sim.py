import time

def count_primes(max_val):
    is_prime = [0] * max_val
    is_prime[0] = 1
    is_prime[1] = 1
    p = 2
    while p * p <= max_val:
        if is_prime[p] == 0:
            i = p * p
            while i < max_val:
                is_prime[i] = 1
                i += p
        p += 1
    
    count = 0
    for i in range(2, max_val):
        if is_prime[i] == 0:
            count += 1
    return count

start = time.time()
count = count_primes(20000000)
dur = int((time.time() - start) * 1000)

print("==============================================")
print("  PYTHON SIEVE(20M) BENCHMARK")
print("  Sure: {} ms".format(dur))
print("  Hesap: {}".format(count))
print("==============================================")
