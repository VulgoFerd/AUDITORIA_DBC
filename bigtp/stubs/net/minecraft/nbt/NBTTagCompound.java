package net.minecraft.nbt;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stub de compilacao/teste com semantica equivalente a NBTTagCompound do 1.7.10
 * para as operacoes usadas pelo TP. NAO e injetado no jogo.
 *
 * getInteger/getLong devolvem 0 quando a tag existe com outro tipo, igual ao
 * comportamento real do Minecraft. Isso permite testar que o TpNbtCore nao se
 * apoia nesse comportamento.
 */
public class NBTTagCompound extends NBTBase {

    private final Map<String, NBTBase> tags = new LinkedHashMap<String, NBTBase>();

    @Override
    public byte func_74732_a() {
        return 10;
    }

    public boolean func_74764_b(String key) {
        return tags.containsKey(key);
    }

    public NBTBase func_74781_a(String key) {
        return tags.get(key);
    }

    public int func_74762_e(String key) {
        NBTBase base = tags.get(key);
        return base instanceof NBTTagInt ? ((NBTTagInt) base).value : 0;
    }

    public long func_74763_f(String key) {
        NBTBase base = tags.get(key);
        return base instanceof NBTTagLong ? ((NBTTagLong) base).value : 0L;
    }

    public void func_74768_a(String key, int value) {
        tags.put(key, new NBTTagInt(value));
    }

    public void func_74772_a(String key, long value) {
        tags.put(key, new NBTTagLong(value));
    }

    /** Apenas para teste: grava uma tag de tipo arbitrario. */
    public void putRaw(String key, NBTBase base) {
        tags.put(key, base);
    }

    /** Apenas para teste. */
    public int rawType(String key) {
        NBTBase base = tags.get(key);
        return base == null ? -1 : (base.func_74732_a() & 0xFF);
    }

    /** Apenas para teste. */
    public int size() {
        return tags.size();
    }
}
