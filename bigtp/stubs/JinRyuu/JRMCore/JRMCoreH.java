package JinRyuu.JRMCore;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;

/** Stub de compilacao/teste. NAO e injetado no jogo. */
public class JRMCoreH {
    public static int curTP;
    public static long curTPLong;

    public static NBTTagCompound nbt(Entity entity, String key) {
        if (entity == null) {
            return null;
        }
        return entity.pres;
    }

    public static String numSep(long value) {
        return Long.toString(value);
    }

    /** Converte TP long para short para exibicao (saturado em Short.MAX_VALUE). */
    public static short tpShort(long value) {
        if (value > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (value < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) value;
    }

    /** Converte TP long para int para compatibilidade legada (saturado em Integer.MAX_VALUE). */
    public static int clampTP(long value) {
        if (value > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (value < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) value;
    }
}
