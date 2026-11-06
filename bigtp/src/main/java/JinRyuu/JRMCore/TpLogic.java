package JinRyuu.JRMCore;

/**
 * Logica pura de TP (64-bit), sem dependencia de Minecraft.
 *
 * Invariante: 0 <= TP <= Long.MAX_VALUE.
 * Politica explicita:
 *  - valor absoluto negativo e rejeitado (NEGATIVE_VALUE);
 *  - ganho negativo e rejeitado (NEGATIVE_AMOUNT);
 *  - ganho que ultrapassa Long.MAX_VALUE e rejeitado (OVERFLOW), sem saturar em silencio;
 *  - gasto negativo e rejeitado (NEGATIVE_AMOUNT);
 *  - gasto maior que o saldo e rejeitado (INSUFFICIENT), sem alterar o saldo;
 *  - qualquer resultado OK respeita o invariante.
 */
public final class TpLogic {

    public static final long MAX_TP = Long.MAX_VALUE;

    private TpLogic() {
    }

    public enum Status {
        OK,
        NEGATIVE_VALUE,
        NEGATIVE_AMOUNT,
        INSUFFICIENT,
        OVERFLOW
    }

    /** Resultado de uma operacao. Nunca nulo. */
    public static final class Result {
        private final Status status;
        private final long value;
        private final long delta;

        private Result(Status status, long value, long delta) {
            this.status = status;
            this.value = value;
            this.delta = delta;
        }

        public Status status() {
            return status;
        }

        /** Valor resultante. Em falha, o saldo atual (inalterado). */
        public long value() {
            return value;
        }

        /** Variacao aplicada (ou pretendida, em falha de overflow). */
        public long delta() {
            return delta;
        }

        public boolean ok() {
            return status == Status.OK;
        }

        @Override
        public String toString() {
            return "Result{" + status + ", value=" + value + ", delta=" + delta + "}";
        }
    }

    /** Cria um resultado de rejeicao explicito (usado pela fachada). */
    public static Result rejected(Status status, long current) {
        return new Result(status, normalize(current), 0L);
    }

    /** Normaliza leitura: valor negativo vira 0. Usado apenas ao interpretar saves. */
    public static long normalize(long value) {
        return value < 0L ? 0L : value;
    }

    /** Define o saldo absoluto. Negativo e rejeitado. */
    public static Result absolute(long value) {
        if (value < 0L) {
            return new Result(Status.NEGATIVE_VALUE, 0L, 0L);
        }
        return new Result(Status.OK, value, 0L);
    }

    /** Soma um ganho. Overflow e rejeitado; o saldo nao e alterado. */
    public static Result gain(long current, long amount) {
        long cur = normalize(current);
        if (amount < 0L) {
            return new Result(Status.NEGATIVE_AMOUNT, cur, 0L);
        }
        if (amount > MAX_TP - cur) {
            return new Result(Status.OVERFLOW, cur, amount);
        }
        return new Result(Status.OK, cur + amount, amount);
    }

    /** Gasta um valor. Gasto acima do saldo e rejeitado; o saldo nao e alterado. */
    public static Result spend(long current, long amount) {
        long cur = normalize(current);
        if (amount < 0L) {
            return new Result(Status.NEGATIVE_AMOUNT, cur, 0L);
        }
        if (amount > cur) {
            return new Result(Status.INSUFFICIENT, cur, amount);
        }
        return new Result(Status.OK, cur - amount, -amount);
    }

    /** Espelho legado: satura em [0, Integer.MAX_VALUE]. */
    public static int mirror(long value) {
        if (value <= 0L) {
            return 0;
        }
        if (value >= (long) Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) value;
    }

    /** Converte um INT legado lido do NBT em long nao negativo. */
    public static long fromLegacyInt(int value) {
        return value < 0 ? 0L : (long) value;
    }

    /** Aplica um delta assinado com verificacao de faixa. */
    public static Result applyDelta(long current, long delta) {
        if (delta >= 0L) {
            return gain(current, delta);
        }
        if (delta == Long.MIN_VALUE) {
            return new Result(Status.INSUFFICIENT, normalize(current), delta);
        }
        return spend(current, -delta);
    }
}
