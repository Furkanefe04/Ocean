package ocean.stdlib;

import java.util.HashSet;
/**
 * Benzersiz eleman kümesi ve küme işlemleri sunan standart jenerik küme (set) koleksiyonu.
 */
public class OceanSet<T> extends HashSet<T> {
    private static final long serialVersionUID = 1L;

    public OceanSet() {
        super();
    }

    public OceanSet(int initialCapacity) {
        super(initialCapacity);
    }
}
