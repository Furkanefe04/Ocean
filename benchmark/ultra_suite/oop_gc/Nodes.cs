using System;
using System.Diagnostics;

class Node {
    public int value;
    public Node next;
    public Node(int v) { this.value = v; }
}

class Nodes {
    static void Main() {
        int limit = 20000000;
        Stopwatch sw = Stopwatch.StartNew();
        Node head = new Node(0);
        Node curr = head;
        for (int i = 1; i < limit; i++) {
            curr.next = new Node(i);
            curr = curr.next;
        }
        long sum = 0;
        curr = head;
        while (curr != null) {
            sum += curr.value;
            curr = curr.next;
        }
        sw.Stop();
        Console.WriteLine("CSHARP Nodes Sum: " + sum + ", Time: " + sw.ElapsedMilliseconds + " ms");
    }
}
