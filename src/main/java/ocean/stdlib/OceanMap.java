package ocean.stdlib;

import java.util.HashMap;
/**
 * Ocean standart kütüphanesinin anahtar-değer eşlemesi sunan jenerik harita (map) koleksiyonu.
 */
public class OceanMap<K, V> extends HashMap<K, V> {
    private static final long serialVersionUID = 1L;

    public OceanMap() {
        super();
    }

    public OceanMap(int initialCapacity) {
        super(initialCapacity);
    }

}
