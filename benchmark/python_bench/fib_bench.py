import time
import sys

# Necessary for large integer string conversion in Python 3.10.7+
sys.set_int_max_str_digits(100000)

def main():
    print("Python Fib(200,000) baslatiliyor...")
    start = time.time()
    
    a = 0
    b = 1
    
    for i in range(2, 200001):
        temp = a + b
        a = b
        b = temp
        
    dur = int((time.time() - start) * 1000)
    print("Python Fib(200,000) Tamamlandi.")
    print("Sure: {} ms".format(dur))
    s = str(b)
    print("Basamak Sayisi: {}".format(len(s)))
    print("DOGRULAMA: {}...{}".format(s[:10], s[-10:]))

if __name__ == "__main__":
    main()
