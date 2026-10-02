class Node {
    int value;
    Node next;
    Node(int v) { this.value = v; }
}

public class Nodes {
    public static void main(String[] args) {
        int limit = 20000000;
        long start = System.currentTimeMillis();
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
        long end = System.currentTimeMillis();
        System.out.println("Java Nodes Sum: " + sum + ", Time: " + (end - start) + " ms");
    }
}
