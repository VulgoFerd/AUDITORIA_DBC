package net.minecraft.nbt;

/** Stub de compilacao/teste. NAO e injetado no jogo. */
public final class NBTTagLong extends NBTBase {
    public final long value;

    public NBTTagLong(long value) {
        this.value = value;
    }

    @Override
    public byte func_74732_a() {
        return 4;
    }
}
