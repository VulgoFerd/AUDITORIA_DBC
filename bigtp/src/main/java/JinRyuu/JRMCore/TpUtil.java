package JinRyuu.JRMCore;

import JinRyuu.JRMCore.p.JRMCorePTpSync;
import JinRyuu.JRMCore.p.PD;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
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
        syncToClient(player);
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
        TpLogic.Result result = writeChecked(playerTag(player), value);
        syncToClient(player);
        return result;
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
        TpLogic.Result result = gain(playerTag(player), amount);
        syncToClient(player);
        return result;
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
        TpLogic.Result result = spend(playerTag(player), amount);
        syncToClient(player);
        return result;
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

    // Combined operations with sync for player targets.
    // As variantes *AndSync delegam para os overloads de EntityPlayer, que ja
    // sincronizam; assim cada operacao envia exatamente um pacote.
    public static void writeAndSync(EntityPlayer player, long value) {
        writeCheckedVoid(player, value);
    }

    public static void gainAndSync(EntityPlayer player, long amount) {
        gainVoid(player, amount);
    }

    public static void spendAndSync(EntityPlayer player, long amount) {
        spendVoid(player, amount);
    }

    // -------------------------------------------------------------- comando

    /**
     * Soma um valor positivo com teto em Long.MAX_VALUE.
     * Usado por /jrmctp. Devolve o saldo final.
     */
    public static long commandAddLong(NBTTagCompound tag, long amount) {
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
    public static long commandTakeLong(NBTTagCompound tag, long amount) {
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
    public static long commandWriteLong(NBTTagCompound tag, long value) {
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

    // Void-returning variants for ASM adapters (CSV expects these exact names)
    public static void commandAdd(NBTTagCompound tag, long amount) {
        commandAddLong(tag, amount);
    }

    public static void commandTake(NBTTagCompound tag, long amount) {
        commandTakeLong(tag, amount);
    }

    public static void commandWrite(NBTTagCompound tag, long value) {
        commandWriteLong(tag, value);
    }

    public static long readUsable(NBTTagCompound tag) {
        TpNbtCore.ReadResult result = TpNbtCore.readAndMigrate(TpNbt.of(tag));
        if (!result.usable()) {
            reportInvalid(result);
            return 0L;
        }
        return result.value();
    }

    public static long readUsable(EntityPlayer player) {
        if (player == null) {
            return 0L;
        }
        NBTTagCompound tag = JRMCoreH.nbt(player, PLAYER_TAG);
        if (tag == null) {
            return 0L;
        }
        return readUsable(tag);
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

    // -------------------------------------------------- ponte legada INT
    // O bytecode antigo le e escreve o espelho INT. A leitura devolve o
    // espelho saturado. A escrita INT e interpretada como variacao contra o
    // espelho:
    //
    //   delta = pedido - espelho(saldoAtual)
    //   novoSaldo = saldoAtual + delta
    //
    // Assim um codigo que faz "getInt - custo" continua gastando o custo, e
    // um saldo LONG acima de Integer.MAX_VALUE nao e truncado. Resultado
    // sempre em [0, Long.MAX_VALUE]. Chaves que nao sao de TP mantem o
    // comportamento original do NBT.

    public static int getTagInt(NBTTagCompound tag, String key) {
        if (tag == null) {
            return 0;
        }
        if (!isTpKey(key)) {
            return tag.func_74762_e(key);
        }
        return getTPInt(tag);
    }

    public static void setTagInt(NBTTagCompound tag, String key, int requested) {
        if (tag == null) {
            throw new IllegalArgumentException("NBTTagCompound nulo em TpUtil.setTagInt");
        }
        if (!isTpKey(key)) {
            tag.func_74768_a(key, requested);
            return;
        }
        writeLegacyInt(tag, requested);
    }

    public static int getPlayerInt(EntityPlayer player, String key) {
        if (player == null) {
            return 0;
        }
        if (!isTpKey(key)) {
            NBTTagCompound tag = JRMCoreH.nbt(player, PLAYER_TAG);
            return tag == null ? 0 : tag.func_74762_e(key);
        }
        return getTPInt(player);
    }

    public static void setPlayerInt(int requested, EntityPlayer player, String key) {
        if (player == null) {
            throw new IllegalArgumentException("EntityPlayer nulo em TpUtil.setPlayerInt");
        }
        NBTTagCompound tag = JRMCoreH.nbt(player, PLAYER_TAG);
        if (tag == null) {
            throw new IllegalStateException("NBT 'pres' ausente para o jogador");
        }
        if (!isTpKey(key)) {
            tag.func_74768_a(key, requested);
            return;
        }
        writeLegacyInt(tag, requested);
        syncToClient(player);
    }

    private static boolean isTpKey(String key) {
        return KEY_INT.equals(key) || KEY_LONG.equals(key);
    }

    private static void writeLegacyInt(NBTTagCompound tag, int requested) {
        long current = readUsable(tag);
        int mirrored = TpLogic.mirror(current);
        long delta = (long) requested - (long) mirrored;
        long next;
        if (delta > 0L && current > Long.MAX_VALUE - delta) {
            next = Long.MAX_VALUE;
            System.err.println("BIGTP: escrita INT legada saturada em Long.MAX_VALUE");
        } else {
            next = current + delta;
        }
        if (next < 0L) {
            next = 0L;
        }
        TpNbtCore.write(TpNbt.of(tag), next);
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

    /**
     * Parseia string para long, usado por /jrmctp.
     * Valor negativo ou invalido vira 0.
     */
    public static long parseLong(String s) {
        if (s == null || s.isEmpty()) {
            return 0L;
        }
        try {
            long v = Long.parseLong(s);
            return v < 0L ? 0L : v;
        } catch (NumberFormatException e) {
            System.err.println("BIGTP: parseLong falhou para: " + s);
            return 0L;
        }
    }

    private static void reportInvalid(TpNbtCore.ReadResult result) {
        System.err.println("BIGTP: NBT de TP invalido, save preservado: " + result);
    }

    /**
     * Sincroniza o TP do jogador com o cliente.
     * Envia pacote JRMCorePTpSync com o saldo atual.
     *
     * Somente jogadores do servidor (EntityPlayerMP) recebem pacotes. Em
     * cliente/integrated server o cast direto causava ClassCastException.
     */
    public static void syncToClient(EntityPlayer player) {
        if (!(player instanceof EntityPlayerMP)) {
            return;
        }
        long current = getTP(player);
        JRMCorePTpSync packet = new JRMCorePTpSync(current);
        PD.sendTo(packet, (EntityPlayerMP) player);
    }
}
