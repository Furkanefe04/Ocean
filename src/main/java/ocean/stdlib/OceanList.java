package ocean.stdlib;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;
/**
 * Ocean standart kütüphanesinin genel amaçlı jenerik dinamik dizi listesi.
 * Güvenli eleman erişimi, dilimleme ve shuffle gibi uzantı fonksiyonları sunar.
 */
public class OceanList<T> extends ArrayList<T> {

    private static final long serialVersionUID = 1L;


    public OceanList() {
        super();
    }

    public OceanList(int initialCapacity) {
        super(initialCapacity);
    }

    public void shuffle() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int sz = size();
        for (int i = sz - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            T tmp = get(i);
            set(i, get(j));
            set(j, tmp);
        }
    }

    public boolean isSorted() {
        return isSorted(this);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> boolean isSorted(OceanList<T> list) {
        if (list == null || list.size() <= 1) {
            return true;
        }
        for (int i = 1; i < list.size(); i++) {
            T prev = list.get(i - 1);
            T curr = list.get(i);
            if (prev == null || curr == null) continue;

            if (prev instanceof Number && curr instanceof Number) {
                if (((Number) prev).doubleValue() > ((Number) curr).doubleValue()) {
                    return false;
                }
            } else if (prev instanceof Comparable && prev.getClass().isInstance(curr)) {
                if (((Comparable) prev).compareTo(curr) > 0) {
                    return false;
                }
            } else {
                if (prev.toString().compareTo(curr.toString()) > 0) {
                    return false;
                }
            }
        }
        return true;
    }

    public OceanList<T> slice(int fromIndex) {
        return slice(fromIndex, size());
    }

    public OceanList<T> slice(int fromIndex, int toIndex) {
        OceanList<T> sub = new OceanList<>();
        sub.addAll(super.subList(fromIndex, toIndex));
        return sub;
    }

    @NotNull
    public OceanList<T> subList(int fromIndex, int toIndex) {
        return slice(fromIndex, toIndex);
    }

}
