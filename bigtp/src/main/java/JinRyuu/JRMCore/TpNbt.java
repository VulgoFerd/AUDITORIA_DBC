package JinRyuu.JRMCore;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Adaptador SRG (runtime 1.7.10) do {@link TpNbtCore}.
 *
 * Nomes SRG confirmados no Forge 1.7.10-10.13.4.1614 (conf/methods.csv):
 *   func_74781_a = getTag(String)  -> NBTBase
 *   func_74732_a = NBTBase.getId() -> byte
 *   func_74762_e = getInteger, func_74763_f = getLong
 *   func_74768_a = setInteger, func_74772_a = setLong
 */
public final class TpNbt implements TpTagView {

    private final NBTTagCompound tag;

    private TpNbt(NBTTagCompound tag) {
        if (tag == null) {
            throw new IllegalArgumentException("NBTTagCompound nulo");
        }
        this.tag = tag;
    }

    public static TpNbt of(NBTTagCompound tag) {
        return new TpNbt(tag);
    }

    @Override
    public int tagId(String key) {
        NBTBase base = tag.getTag(key);
        if (base == null) {
            return ABSENT;
        }
        return base.getId() & 0xFF;
    }

    @Override
    public int getInt(String key) {
        return tag.getInteger(key);
    }

    @Override
    public long getLong(String key) {
        return tag.getLong(key);
    }

    @Override
    public void setInt(String key, int value) {
        tag.setInteger(key, value);
    }

    @Override
    public void setLong(String key, long value) {
        tag.setLong(key, value);
    }
}
