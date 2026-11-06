package bigtp;

import JinRyuu.JRMCore.TpNbtCore;
import JinRyuu.JRMCore.TpNbtCore.ReadResult;
import JinRyuu.JRMCore.TpNbtCore.Status;
import JinRyuu.JRMCore.TpTagView;

/** Testes de leitura, escrita, tipos e migracao do NBT. */
public final class TpNbtTest {

    private final Harness h;

    public TpNbtTest(Harness h) {
        this.h = h;
    }

    public void run() {
        h.section("TpNbtCore");

        // Ausencia
        FakeTag absent = new FakeTag();
        ReadResult r = TpNbtCore.read(absent);
        h.eq("ausente status", r.status(), Status.ABSENT);
        h.eq("ausente valor", r.value(), 0L);

        // LONG valido
        FakeTag longTag = new FakeTag();
        longTag.putRaw(TpNbtCore.KEY_LONG, TpTagView.TAG_LONG, 3000000000L);
        r = TpNbtCore.read(longTag);
        h.eq("long status", r.status(), Status.LONG);
        h.eq("long valor", r.value(), 3000000000L);
        h.eq("long valor 1e12", readValue(TpNbtCore.KEY_LONG, TpTagView.TAG_LONG,
                1000000000000L), 1000000000000L);
        h.eq("long Long.MAX", readValue(TpNbtCore.KEY_LONG, TpTagView.TAG_LONG,
                Long.MAX_VALUE), Long.MAX_VALUE);

        // INT legado + migracao
        FakeTag intTag = new FakeTag();
        intTag.putRaw(TpNbtCore.KEY_INT, TpTagView.TAG_INT, Integer.MAX_VALUE);
        r = TpNbtCore.read(intTag);
        h.eq("int legado status", r.status(), Status.INT_LEGACY);
        h.eq("int legado valor", r.value(), (long) Integer.MAX_VALUE);
        h.eq("int legado nao migra na leitura",
                intTag.rawType(TpNbtCore.KEY_LONG), TpTagView.ABSENT);

        ReadResult migrated = TpNbtCore.readAndMigrate(intTag);
        h.eq("migracao status", migrated.status(), Status.INT_LEGACY);
        h.eq("migracao cria long", intTag.rawType(TpNbtCore.KEY_LONG), TpTagView.TAG_LONG);
        h.eq("migracao preserva valor", intTag.getLong(TpNbtCore.KEY_LONG),
                (long) Integer.MAX_VALUE);
        h.eq("migracao preserva espelho", intTag.getInt(TpNbtCore.KEY_INT),
                Integer.MAX_VALUE);

        // Migracao repetida (idempotente)
        ReadResult again = TpNbtCore.readAndMigrate(intTag);
        h.eq("migracao repetida status", again.status(), Status.LONG);
        h.eq("migracao repetida valor", again.value(), (long) Integer.MAX_VALUE);
        h.eq("migracao repetida valor 2", TpNbtCore.read(intTag).value(),
                (long) Integer.MAX_VALUE);

        // jrmcTplong gravado como INT (save intermediario)
        FakeTag cross = new FakeTag();
        cross.putRaw(TpNbtCore.KEY_LONG, TpTagView.TAG_INT, 1234567);
        r = TpNbtCore.read(cross);
        h.eq("long-key-int status", r.status(), Status.LONG_KEY_INT);
        h.eq("long-key-int valor", r.value(), 1234567L);
        TpNbtCore.readAndMigrate(cross);
        h.eq("long-key-int migrado", cross.rawType(TpNbtCore.KEY_LONG), TpTagView.TAG_LONG);

        // jrmcTpint gravado como LONG
        FakeTag cross2 = new FakeTag();
        cross2.putRaw(TpNbtCore.KEY_INT, TpTagView.TAG_LONG, 4000000000L);
        r = TpNbtCore.read(cross2);
        h.eq("int-key-long status", r.status(), Status.INT_KEY_LONG);
        h.eq("int-key-long valor", r.value(), 4000000000L);
        TpNbtCore.readAndMigrate(cross2);
        h.eq("int-key-long migrado", cross2.getLong(TpNbtCore.KEY_LONG), 4000000000L);

        // Tipo incorreto: string em jrmcTplong
        FakeTag bad = new FakeTag();
        bad.putRaw(TpNbtCore.KEY_LONG, 8, 0L);
        r = TpNbtCore.read(bad);
        h.eq("tipo incorreto status", r.status(), Status.INVALID_TYPE);
        h.eq("tipo incorreto raw id", r.tagId(), 8);
        h.eq("tipo incorreto valor 0", r.value(), 0L);
        TpNbtCore.readAndMigrate(bad);
        h.eq("tipo incorreto nao sobrescreve", bad.rawType(TpNbtCore.KEY_LONG), 8);
        h.eq("tipo incorreto nao cria int", bad.rawType(TpNbtCore.KEY_INT),
                TpTagView.ABSENT);

        // Negativo armazenado: nao sobrescrever
        FakeTag negative = new FakeTag();
        negative.putRaw(TpNbtCore.KEY_LONG, TpTagView.TAG_LONG, -42L);
        r = TpNbtCore.read(negative);
        h.eq("negativo status", r.status(), Status.NEGATIVE_STORED);
        h.eq("negativo valor 0", r.value(), 0L);
        TpNbtCore.readAndMigrate(negative);
        h.eq("negativo nao sobrescreve", negative.getLong(TpNbtCore.KEY_LONG), -42L);

        // Escrita absoluta
        FakeTag write = new FakeTag();
        TpNbtCore.write(write, 3000000000L);
        h.eq("write long", write.getLong(TpNbtCore.KEY_LONG), 3000000000L);
        h.eq("write espelho", write.getInt(TpNbtCore.KEY_INT), Integer.MAX_VALUE);
        h.eq("write tipo long", write.rawType(TpNbtCore.KEY_LONG), TpTagView.TAG_LONG);
        h.eq("write tipo int", write.rawType(TpNbtCore.KEY_INT), TpTagView.TAG_INT);

        FakeTag small = new FakeTag();
        TpNbtCore.write(small, 123L);
        h.eq("write pequeno espelho", small.getInt(TpNbtCore.KEY_INT), 123);
        TpNbtCore.write(small, -1L);
        h.eq("write negativo normaliza", small.getLong(TpNbtCore.KEY_LONG), 0L);

        // Serializacao / desserializacao
        FakeTag original = new FakeTag();
        TpNbtCore.write(original, 1000000000000L);
        FakeTag restored = FakeTag.deserialize(original.serialize());
        h.eq("round-trip long", restored.getLong(TpNbtCore.KEY_LONG), 1000000000000L);
        h.eq("round-trip espelho", restored.getInt(TpNbtCore.KEY_INT), Integer.MAX_VALUE);
        h.eq("round-trip status", TpNbtCore.read(restored).status(), Status.LONG);

        // Dois jogadores independentes
        FakeTag p1 = new FakeTag();
        FakeTag p2 = new FakeTag();
        TpNbtCore.write(p1, 5000000000L);
        TpNbtCore.write(p2, 7L);
        h.eq("jogador 1", TpNbtCore.read(p1).value(), 5000000000L);
        h.eq("jogador 2", TpNbtCore.read(p2).value(), 7L);
        TpNbtCore.write(p2, 9000000000L);
        h.eq("jogador 1 intacto", TpNbtCore.read(p1).value(), 5000000000L);
        h.eq("jogador 2 novo", TpNbtCore.read(p2).value(), 9000000000L);
    }

    private long readValue(String key, int type, long value) {
        FakeTag tag = new FakeTag();
        tag.putRaw(key, type, value);
        return TpNbtCore.read(tag).value();
    }
}
