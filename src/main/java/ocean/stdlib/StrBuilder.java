package ocean.stdlib;

import java.io.Serializable;
import java.util.Arrays;
/**
 * Yüksek performanslı ve yeniden kullanılabilir karakter dizisi (string) oluşturucu.
 * Bitişik char[] arabelleği üzerinde doğrudan manipülasyon yaparak bellek tahsisi ve GC yükünü en aza indirir.
 */
public final class StrBuilder implements Serializable {

    int length;
    char[] buffer;

    private static final long serialVersionUID = 1L;

    public StrBuilder() {
        this(16);
    }

    public StrBuilder(int capacity) {
        length = 0;
        buffer = new char[Math.max(capacity, 16)];
    }

    public StrBuilder(String str) {
        this(str != null ? str.toCharArray() : new char[0]);
    }

    public StrBuilder(CharSequence seq) {
        this(seq != null ? seq.toString().toCharArray() : new char[0]);
    }

    public StrBuilder(char[] str) {
        if (str != null) {
            length = str.length;
            buffer = Arrays.copyOf(str, Math.max(str.length + 16, 16));
        } else {
            length = 0;
            buffer = new char[16];
        }
    }

    public StrBuilder(OceanString str) {
        this(str != null ? str.toString().toCharArray() : new char[0]);
    }

    public StrBuilder append(Object obj) {
        if (obj == null) {
            return append("null");
        }
        return append(obj.toString());
    }

    public StrBuilder append(String str) {
        if (str == null) {
            str = "null";
        }
        int len = str.length();
        ensureCapacity(length + len);
        str.getChars(0, len, buffer, length);
        length += len;
        return this;
    }

    public StrBuilder append(OceanString str){
        return append(str != null ? str.toString() : "null");
    }

    public StrBuilder append(char[] str) {
        if (str != null) {
            this.append(str, str.length);
        }
        return this;
    }

    public StrBuilder append(char[] str, int len) {
        if (str != null && len > 0) {
            ensureCapacity(len + length);
            System.arraycopy(str, 0, buffer, length, len);
            length += len;
        }
        return this;
    }

    public StrBuilder append(boolean b) {
        if (b) {
            ensureCapacity(length + 4);
            buffer[length++] = 't';
            buffer[length++] = 'r';
            buffer[length++] = 'u';
        } else {
            ensureCapacity(length + 5);
            buffer[length++] = 'f';
            buffer[length++] = 'a';
            buffer[length++] = 'l';
            buffer[length++] = 's';
        }
        buffer[length++] = 'e';
        return this;
    }

    public StrBuilder append(char c) {
        ensureCapacity(length + 1);
        buffer[length++] = c;
        return this;
    }

    public StrBuilder append(int i) {
        if (i == Integer.MIN_VALUE) {
            append("-2147483648");
            return this;
        }
        if (i == Integer.MAX_VALUE) {
            append("2147483647");
            return this;
        }
        if (i < 0) {
            append('-');
            i = -i;
        }
        if (i == 0) {
            append('0');
            return this;
        }
        int start = length;
        while (i > 0) {
            ensureCapacity(length + 1);
            buffer[length++] = (char)('0' + (i % 10));
            i /= 10;
        }
        for (int l = start, r = length - 1; l < r; l++, r--) {
            char tmp = buffer[l];
            buffer[l] = buffer[r];
            buffer[r] = tmp;
        }
        return this;
    }

    public StrBuilder append(long lng) {
        this.append(String.valueOf(lng));
        return this;
    }

    public StrBuilder append(float f) {
        this.append(String.valueOf(f));
        return this;
    }

    public StrBuilder append(double d) {
        this.append(String.valueOf(d));
        return this;
    }

    public StrBuilder delete(int start, int end) {
        if (start < 0) throw new StringIndexOutOfBoundsException(start);
        if (end > length) end = length;
        if (start > end) throw new StringIndexOutOfBoundsException();
        int len = end - start;
        if (len > 0) {
            System.arraycopy(buffer, end, buffer, start, length - end);
            length -= len;
        }
        return this;
    }

    public StrBuilder deleteCharAt(int index) {
        if (index < 0 || index >= length) {
            throw new StringIndexOutOfBoundsException(index);
        }
        System.arraycopy(buffer, index + 1, buffer, index, length - index - 1);
        length--;
        return this;
    }

    public StrBuilder replace(int start, int end, String str) {
        if (start < 0) throw new StringIndexOutOfBoundsException(start);
        if (start > length) throw new StringIndexOutOfBoundsException("start > length");
        if (start > end) throw new StringIndexOutOfBoundsException("start > end");
        if (str == null) str = "null";

        if (end > length) end = length;
        int strLen = str.length();
        int newLen = length + strLen - (end - start);
        ensureCapacity(newLen);

        System.arraycopy(buffer, end, buffer, start + strLen, length - end);
        str.getChars(0, strLen, buffer, start);
        length = newLen;
        return this;
    }

    public int indexOf(String str) {
        return this.indexOf(str, 0);
    }

    public int indexOf(String str, int fromIndex) {
        if (str == null) return -1;
        if (fromIndex < 0) fromIndex = 0;
        int strLen = str.length();
        if (strLen == 0) return Math.min(fromIndex, length);
        if (fromIndex >= length) return -1;

        char first = str.charAt(0);
        int max = length - strLen;

        for (int i = fromIndex; i <= max; i++) {
            if (buffer[i] != first) {
                while (++i <= max && buffer[i] != first);
            }
            if (i <= max) {
                int j = i + 1;
                int end = j + strLen - 1;
                for (int k = 1; j < end && buffer[j] == str.charAt(k); j++, k++);
                if (j == end) return i;
            }
        }
        return -1;
    }

    public StrBuilder reverse() {
        int n = length - 1;
        for (int j = (n - 1) >> 1; j >= 0; j--) {
            int k = n - j;
            char cj = buffer[j];
            char ck = buffer[k];
            buffer[j] = ck;
            buffer[k] = cj;
        }
        return this;
    }

    public StrBuilder repeat(CharSequence cs, int count) {
        if (cs != null && count > 0) {
            int csLen = cs.length();
            ensureCapacity(length + csLen * count);
            for (int i = 0; i < count; i++) {
                for (int j = 0; j < csLen; j++) {
                    buffer[length++] = cs.charAt(j);
                }
            }
        }
        return this;
    }

    public String toString() {
        if (length == 0) {
            return "";
        }
        return new String(buffer, 0, length);
    }

    public int length() {
        return length;
    }

    public void clear() {
        length = 0;
    }

    private void ensureCapacity(int minCapacity) {
        int oldCapacity = buffer.length;
        if (oldCapacity < minCapacity) {
            int newCapacity = (oldCapacity * 3) / 2 + 1;
            if (newCapacity < minCapacity) {
                newCapacity = minCapacity;
            }
            buffer = Arrays.copyOf(buffer, newCapacity);
        }
    }
}
