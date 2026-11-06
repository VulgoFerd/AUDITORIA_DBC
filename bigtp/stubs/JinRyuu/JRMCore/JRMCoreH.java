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
}
