package bigtp;

import JinRyuu.JRMCore.TpTagView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Implementacao em memoria de TpTagView para testes puros e serializacao.
 * Guarda o tipo real de cada tag, como o NBT real.
 */
public final class FakeTag implements TpTagView {

    private final Map<String, Entry> tags = new LinkedHashMap<String, Entry>();

    private static final class Entry {
        final int type;
        final long value;

        Entry(int type, long value) {
            this.type = type;
            this.value = value;
        }
    }

    @Override
    public int tagId(String key) {
        Entry e = tags.get(key);
        return e == null ? ABSENT : e.type;
    }

    @Override
    public int getInt(String key) {
        Entry e = tags.get(key);
        return e != null && e.type == TAG_INT ? (int) e.value : 0;
    }

    @Override
    public long getLong(String key) {
        Entry e = tags.get(key);
        return e != null && e.type == TAG_LONG ? e.value : 0L;
    }

    @Override
    public void setInt(String key, int value) {
        tags.put(key, new Entry(TAG_INT, value));
    }

    @Override
    public void setLong(String key, long value) {
        tags.put(key, new Entry(TAG_LONG, value));
    }

    public void putRaw(String key, int type, long value) {
        tags.put(key, new Entry(type, value));
    }

    public int rawType(String key) {
        return tagId(key);
    }

    public int size() {
        return tags.size();
    }

    /** Serializa as chaves relevantes em um formato binario simples. */
    public byte[] serialize() {
        byte[] out = new byte[2 * 9];
        int p = 0;
        out[p++] = (byte) tagId("jrmcTplong");
        out[p++] = (byte) tagId("jrmcTpint");
        out = writeLong(out, p, getLong("jrmcTplong"));
        p += 8;
        long mirror = getInt("jrmcTpint") & 0xFFFFFFFFL;
        out = writeLong(out, p, mirror);
        return out;
    }

    public static FakeTag deserialize(byte[] data) {
        FakeTag tag = new FakeTag();
        int longId = data[0] & 0xFF;
        int intId = data[1] & 0xFF;
        long longValue = readLong(data, 2);
        long intValue = readLong(data, 10);
        if (longId != ABSENT) {
            tag.putRaw("jrmcTplong", longId, longValue);
        }
        if (intId != ABSENT) {
            tag.putRaw("jrmcTpint", intId, intValue);
        }
        return tag;
    }

    private static byte[] writeLong(byte[] data, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            data[offset + i] = (byte) (value >>> (56 - 8 * i));
        }
        return data;
    }

    private static long readLong(byte[] data, int offset) {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[offset + i] & 0xFFL);
        }
        return value;
    }
}
