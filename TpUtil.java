package JinRyuu.JRMCore;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Autoridade unica para TP 64-bit.
 * jrmcTplong e a fonte de verdade; jrmcTpint e apenas espelho saturado (0..Integer.MAX_VALUE).
 */
public final class TpUtil {

    public static final String KEY_LONG = "jrmcTplong";
    public static final String KEY_INT = "jrmcTpint";

    private TpUtil() {
    }

    public static long getTP(NBTTagCompound tag) {
        if (tag == null) return 0L;
        if (tag.func_74764_b(KEY_LONG)) return tag.func_74763_f(KEY_LONG);
        return (long) tag.func_74762_e(KEY_INT);
    }

    public static long getTP(EntityPlayer p) {
        if (p == null) return 0L;
        return getTP(JRMCoreH.nbt(p, "pres"));
    }

    public static int clamp(long v) {
        if (v <= 0L) return 0;
        if (v >= (long) Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) v;
    }

    public static int getTPInt(NBTTagCompound tag) {
        return clamp(getTP(tag));
    }

    public static int getTPInt(EntityPlayer p) {
        return clamp(getTP(p));
    }

    public static void setTP(NBTTagCompound tag, long v) {
        if (tag == null) return;
        tag.func_74772_a(KEY_LONG, v);
        tag.func_74768_a(KEY_INT, clamp(v));
        JRMCoreH.curTPLong = v;
        JRMCoreH.curTP = clamp(v);
    }

    public static void setTP(EntityPlayer p, long v) {
        if (p == null) return;
        setTP(JRMCoreH.nbt(p, "pres"), v);
    }

    public static void addTP(NBTTagCompound tag, long gain) {
        if (tag == null) return;
        setTP(tag, getTP(tag) + gain);
    }

    public static void addTP(EntityPlayer p, long gain) {
        if (p == null) return;
        setTP(p, getTP(p) + gain);
    }

    /** Escrita legada de int: v e tratado como valor absoluto dentro da janela saturada. */
    public static void setMirror(NBTTagCompound tag, int v) {
        if (tag == null) return;
        long cur = getTP(tag);
        setTP(tag, cur + ((long) v - (long) clamp(cur)));
    }

    public static void setMirror(EntityPlayer p, int v) {
        if (p == null) return;
        long cur = getTP(p);
        setTP(p, cur + ((long) v - (long) clamp(cur)));
    }

    public static void setClient(long v) {
        JRMCoreH.curTPLong = v;
        JRMCoreH.curTP = clamp(v);
    }

    public static String shortTP(long v) {
        if (v < 10000000000L) return JRMCoreH.numSep(v);
        long unit;
        String suffix;
        if (v >= 1000000000000000000L) {
            unit = 1000000000000000000L;
            suffix = "Qi";
        } else if (v >= 1000000000000000L) {
            unit = 1000000000000000L;
            suffix = "Q";
        } else if (v >= 1000000000000L) {
            unit = 1000000000000L;
            suffix = "T";
        } else {
            unit = 1000000000L;
            suffix = "B";
        }
        long ip = v / unit;
        long rem = v % unit;
        int dec = (int) (rem / (unit / 10L));
        return ip + "." + dec + suffix;
    }
}
