package JinRyuu.JRMCore;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Fachada do TP 64-bit.
 *
 * Operacoes separadas por intencao:
 *  - leitura: {@link #getTP(NBTTagCompound)}, {@link #getTP(EntityPlayer)};
 *  - escrita absoluta em NBT: {@link #write(NBTTagCompound, long)};
 *  - ganho: {@link #gain(NBTTagCompound, long)};
 *  - gasto: {@link #spend(NBTTagCompound, long)};
 *  - estado local do cliente: {@link #applyLocal(long)};
 *  - estado recebido da rede: {@link #acceptNetwork(long)};
 *  - espelho legado: {@link #mirror(long)} / {@link #getTPInt(NBTTagCompound)}.
 *
 * Nenhuma escrita de NBT altera JRMCoreH.curTPLong ou JRMCoreH.curTP.
 */
public final class TpUtil {

    public static final String KEY_LONG = TpNbtCore.KEY_LONG;
    public static final String KEY_INT = TpNbtCore.KEY_INT;
    public static final String PLAYER_TAG = "pres";

    private TpUtil() {
    }

    // ---------------------------------------------------------------- leitura

    /** Inspeciona sem migrar. */
    public static TpNbtCore.ReadResult inspect(NBTTagCompound tag) {
        if (tag == null) {
            return null;
        }
        return TpNbtCore.read(TpNbt.of(tag));
    }

    /**
     * Le e migra o NBT do jogador. Tipo invalido e reportado no stderr e
     * tratado como 0, sem sobrescrever o save.
     */
    public static long getTP(NBTTagCompound tag) {
        if (tag == null) {
            return 0L;
        }
        TpNbtCore.ReadResult result = TpNbtCore.readAndMigrate(TpNbt.of(tag));
        if (!result.usable()) {
            reportInvalid(result);
            return 0L;
        }
        return result.value();
    }

    public static long getTP(EntityPlayer player) {
        if (player == null) {
            return 0L;
        }
        return getTP(JRMCoreH.nbt(player, PLAYER_TAG));
    }

    /** Espelho legado saturado. */
    public static int getTPInt(NBTTagCompound tag) {
        return TpLogic.mirror(getTP(tag));
    }

    public static int getTPInt(EntityPlayer player) {
        return TpLogic.mirror(getTP(player));
    }

    // ------------------------------------------------------- escrita absoluta

    /** Escrita absoluta em NBT. Valor negativo lanca IllegalArgumentException. */
    public static void write(NBTTagCompound tag, long value) {
        if (tag == null) {
            throw new IllegalArgumentException("NBTTagCompound nulo em TpUtil.write");
        }
        TpLogic.Result result = TpLogic.absolute(value);
        if (!result.ok()) {
            throw new IllegalArgumentException("TP absoluto invalido: " + value
                    + " (" + result.status() + ")");
        }
        TpNbtCore.write(TpNbt.of(tag), result.value());
    }

    public static void write(EntityPlayer player, long value) {
        NBTTagCompound tag = playerTag(player);
        write(tag, value);
    }

    // ------------------------------------------------------------------ ganho

    public static TpLogic.Result gain(NBTTagCompound tag, long amount) {
        if (tag == null) {
            throw new IllegalArgumentException("NBTTagCompound nulo em TpUtil.gain");
        }
        TpNbtCore.ReadResult current = TpNbtCore.readAndMigrate(TpNbt.of(tag));
        if (!current.usable()) {
            reportInvalid(current);
            return TpLogic.rejected(TpLogic.Status.NEGATIVE_VALUE, 0L);
        }
        TpLogic.Result result = TpLogic.gain(current.value(), amount);
        if (result.ok()) {
            TpNbtCore.write(TpNbt.of(tag), result.value());
        }
        return result;
    }

    public static TpLogic.Result gain(EntityPlayer player, long amount) {
        return gain(playerTag(player), amount);
    }

    // ------------------------------------------------------------------ gasto

    public static TpLogic.Result spend(NBTTagCompound tag, long amount) {
        if (tag == null) {
            throw new IllegalArgumentException("NBTTagCompound nulo em TpUtil.spend");
        }
        TpNbtCore.ReadResult current = TpNbtCore.readAndMigrate(TpNbt.of(tag));
        if (!current.usable()) {
            reportInvalid(current);
            return TpLogic.rejected(TpLogic.Status.NEGATIVE_VALUE, 0L);
        }
        TpLogic.Result result = TpLogic.spend(current.value(), amount);
        if (result.ok()) {
            TpNbtCore.write(TpNbt.of(tag), result.value());
        }
        return result;
    }

    public static TpLogic.Result spend(EntityPlayer player, long amount) {
        return spend(playerTag(player), amount);
    }

    // --------------------------------------------------------------- estado

    /** Atualiza o estado local do cliente. */
    public static void applyLocal(long value) {
        TpClientState.apply(value);
    }

    /** Aceita estado recebido da rede. */
    public static void acceptNetwork(long value) {
        TpNetwork.accept(value);
    }

    /** Espelho legado. */
    public static int mirror(long value) {
        return TpLogic.mirror(value);
    }

    // -------------------------------------------------------------- helpers

    private static NBTTagCompound playerTag(EntityPlayer player) {
        if (player == null) {
            throw new IllegalArgumentException("EntityPlayer nulo");
        }
        NBTTagCompound tag = JRMCoreH.nbt(player, PLAYER_TAG);
        if (tag == null) {
            throw new IllegalStateException("NBT 'pres' ausente para o jogador");
        }
        return tag;
    }

    private static void reportInvalid(TpNbtCore.ReadResult result) {
        System.err.println("BIGTP: NBT de TP invalido, save preservado: " + result);
    }
}
