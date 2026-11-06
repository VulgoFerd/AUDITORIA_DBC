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

    /**
     * Escrita absoluta sem excecao. Valor negativo e tratado como 0 e
     * reportado no stderr. Usado por bytecode legado que pode calcular
     * valores negativos.
     */
    public static TpLogic.Result writeChecked(NBTTagCompound tag, long value) {
        TpLogic.Result result = TpLogic.absolute(value);
        if (!result.ok()) {
            System.err.println("BIGTP: escrita absoluta rejeitada: " + value
                    + " (" + result.status() + ")");
            result = TpLogic.absolute(0L);
        }
        TpNbtCore.write(TpNbt.of(tag), result.value());
        return result;
    }

    public static TpLogic.Result writeChecked(EntityPlayer player, long value) {
        return writeChecked(playerTag(player), value);
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

    // ----------------------------------------------------- adaptadores ASM
    // Usados apenas pelo bytecode reescrito. Mantem a intencao explicita.

    public static void gainVoid(NBTTagCompound tag, long amount) {
        gain(tag, amount);
    }

    public static void gainVoid(EntityPlayer player, long amount) {
        gain(player, amount);
    }

    public static void spendVoid(NBTTagCompound tag, long amount) {
        spend(tag, amount);
    }

    public static void spendVoid(EntityPlayer player, long amount) {
        spend(player, amount);
    }

    public static void writeCheckedVoid(NBTTagCompound tag, long value) {
        writeChecked(tag, value);
    }

    public static void writeCheckedVoid(EntityPlayer player, long value) {
        writeChecked(player, value);
    }

    // -------------------------------------------------------------- comando

    /**
     * Soma um valor positivo com teto em Long.MAX_VALUE.
     * Usado por /jrmctp. Devolve o saldo final.
     */
    public static long commandAdd(NBTTagCompound tag, long amount) {
        if (tag == null) {
            throw new IllegalArgumentException("NBTTagCompound nulo em TpUtil.commandAdd");
        }
        long current = readUsable(tag);
        long sum = current + amount;
        if (amount > 0L && sum < current) {
            System.err.println("BIGTP: /jrmctp soma saturada em Long.MAX_VALUE");
            sum = Long.MAX_VALUE;
        }
        TpNbtCore.write(TpNbt.of(tag), sum);
        return sum;
    }

    /**
     * Subtrai um valor positivo com piso em 0.
     * Usado por /jrmctp. Devolve o saldo final.
     */
    public static long commandTake(NBTTagCompound tag, long amount) {
        if (tag == null) {
            throw new IllegalArgumentException("NBTTagCompound nulo em TpUtil.commandTake");
        }
        long current = readUsable(tag);
        long result = amount >= current ? 0L : current - amount;
        TpNbtCore.write(TpNbt.of(tag), result);
        return result;
    }

    /**
     * Define um saldo absoluto pelo comando. Negativo vira 0 e e reportado.
     */
    public static long commandWrite(NBTTagCompound tag, long value) {
        if (tag == null) {
            throw new IllegalArgumentException("NBTTagCompound nulo em TpUtil.commandWrite");
        }
        long normalized = value < 0L ? 0L : value;
        if (value < 0L) {
            System.err.println("BIGTP: /jrmctp recebeu valor negativo, aplicado 0");
        }
        TpNbtCore.write(TpNbt.of(tag), normalized);
        return normalized;
    }

    private static long readUsable(NBTTagCompound tag) {
        TpNbtCore.ReadResult result = TpNbtCore.readAndMigrate(TpNbt.of(tag));
        if (!result.usable()) {
            reportInvalid(result);
            return 0L;
        }
        return result.value();
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
