package net.minecraft.nbt;

/** Stub de compilacao/teste. NAO e injetado no jogo. */
public final class NBTTagInt extends NBTBase {
    public final int value;

    public NBTTagInt(int value) {
        this.value = value;
    }

    @Override
    public byte func_74732_a() {
        return 3;
    }
}
