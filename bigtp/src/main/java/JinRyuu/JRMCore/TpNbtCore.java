package JinRyuu.JRMCore;

/**
 * Nucleo de leitura/escrita/migracao do TP no NBT.
 *
 * Regras:
 *  - jrmcTplong e a autoridade (TAG_Long = 4);
 *  - jrmcTpint e espelho legado saturado (TAG_Int = 3);
 *  - a presenca da chave nunca e usada como prova de tipo: o tipo real da tag
 *    e consultado em {@link TpTagView#tagId(String)};
 *  - tipo incorreto NAO e convertido nem sobrescrito: retorna INVALID_TYPE;
 *  - valor negativo armazenado NAO e sobrescrito: retorna NEGATIVE_STORED;
 *  - migracao INT -> LONG preserva o valor e e idempotente;
 *  - escrita absoluta grava as duas chaves, com espelho saturado.
 */
public final class TpNbtCore {

    public static final String KEY_LONG = "jrmcTplong";
    public static final String KEY_INT = "jrmcTpint";

    private TpNbtCore() {
    }

    public enum Status {
        /** jrmcTplong presente e do tipo LONG. */
        LONG,
        /** jrmcTplong presente como INT (save intermediario). Migrar. */
        LONG_KEY_INT,
        /** Somente jrmcTpint do tipo INT. Migrar. */
        INT_LEGACY,
        /** jrmcTpint contem um LONG. Migrar para a chave long. */
        INT_KEY_LONG,
        /** Nenhuma das chaves existe. */
        ABSENT,
        /** Valor negativo armazenado. Nao sobrescrever. */
        NEGATIVE_STORED,
        /** Tipo de tag inesperado. Nao sobrescrever. */
        INVALID_TYPE
    }

    public static final class ReadResult {
        private final Status status;
        private final long value;
        private final int tagId;
        private final String key;

        private ReadResult(Status status, long value, int tagId, String key) {
            this.status = status;
            this.value = value;
            this.tagId = tagId;
            this.key = key;
        }

        public Status status() {
            return status;
        }

        /** Valor interpretado. Em INVALID_TYPE/NEGATIVE_STORED, 0. */
        public long value() {
            return value;
        }

        /** Tipo real encontrado (ou ABSENT). */
        public int tagId() {
            return tagId;
        }

        /** Chave que determinou o resultado. */
        public String key() {
            return key;
        }

        public boolean usable() {
            return status != Status.INVALID_TYPE && status != Status.NEGATIVE_STORED;
        }

        public boolean needsMigration() {
            return status == Status.LONG_KEY_INT
                    || status == Status.INT_LEGACY
                    || status == Status.INT_KEY_LONG;
        }

        @Override
        public String toString() {
            return "ReadResult{" + status + ", value=" + value
                    + ", tagId=" + tagId + ", key=" + key + "}";
        }
    }

    /** Le sem alterar nada. */
    public static ReadResult read(TpTagView view) {
        if (view == null) {
            throw new IllegalArgumentException("TpTagView nulo");
        }

        int longId = view.tagId(KEY_LONG);
        if (longId == TpTagView.TAG_LONG) {
            long value = view.getLong(KEY_LONG);
            if (value < 0L) {
                return new ReadResult(Status.NEGATIVE_STORED, 0L, longId, KEY_LONG);
            }
            return new ReadResult(Status.LONG, value, longId, KEY_LONG);
        }
        if (longId == TpTagView.TAG_INT) {
            long value = TpLogic.fromLegacyInt(view.getInt(KEY_LONG));
            return new ReadResult(Status.LONG_KEY_INT, value, longId, KEY_LONG);
        }
        if (longId != TpTagView.ABSENT) {
            return new ReadResult(Status.INVALID_TYPE, 0L, longId, KEY_LONG);
        }

        int intId = view.tagId(KEY_INT);
        if (intId == TpTagView.TAG_INT) {
            long value = TpLogic.fromLegacyInt(view.getInt(KEY_INT));
            return new ReadResult(Status.INT_LEGACY, value, intId, KEY_INT);
        }
        if (intId == TpTagView.TAG_LONG) {
            long value = view.getLong(KEY_INT);
            if (value < 0L) {
                return new ReadResult(Status.NEGATIVE_STORED, 0L, intId, KEY_INT);
            }
            return new ReadResult(Status.INT_KEY_LONG, value, intId, KEY_INT);
        }
        if (intId != TpTagView.ABSENT) {
            return new ReadResult(Status.INVALID_TYPE, 0L, intId, KEY_INT);
        }

        return new ReadResult(Status.ABSENT, 0L, TpTagView.ABSENT, null);
    }

    /**
     * Le e, quando o valor veio de formato legado, regrava no formato atual.
     * Nunca toca em INVALID_TYPE nem em NEGATIVE_STORED.
     */
    public static ReadResult readAndMigrate(TpTagView view) {
        ReadResult result = read(view);
        if (result.needsMigration()) {
            write(view, result.value());
        }
        return result;
    }

    /**
     * Escrita absoluta. Grava jrmcTplong e o espelho jrmcTpint saturado.
     * Nao altera nenhum estado global do cliente.
     */
    public static void write(TpTagView view, long value) {
        if (view == null) {
            throw new IllegalArgumentException("TpTagView nulo");
        }
        long normalized = TpLogic.normalize(value);
        view.setLong(KEY_LONG, normalized);
        view.setInt(KEY_INT, TpLogic.mirror(normalized));
    }
}
