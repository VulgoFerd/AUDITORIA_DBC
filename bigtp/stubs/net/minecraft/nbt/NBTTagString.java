package net.minecraft.nbt;

/** Stub de compilacao/teste. NAO e injetado no jogo. */
public final class NBTTagString extends NBTBase {
    public final String value;

    public NBTTagString(String value) {
        this.value = value;
    }

    @Override
    public byte func_74732_a() {
        return 8;
    }
}
