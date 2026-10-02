import time

class Node:
    __slots__ = ['value', 'next']
    def __init__(self, value):
        self.value = value
        self.next = None

def main():
    limit = 20000000
    start = time.time()
    
    head = Node(0)
    curr = head
    for i in range(1, limit):
        new_node = Node(i)
        curr.next = new_node
        curr = new_node
        
    total_sum = 0
    curr = head
    while curr:
        total_sum += curr.value
        curr = curr.next
        
    end = time.time()
    print("PYTHON Nodes Sum: {}, Time: {:.1f} ms".format(total_sum, (end - start) * 1000))

if __name__ == "__main__":
    main()
