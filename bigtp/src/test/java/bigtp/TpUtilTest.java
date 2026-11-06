package bigtp;

import JinRyuu.JRMCore.TpLogic;
import JinRyuu.JRMCore.TpNbtCore;
import JinRyuu.JRMCore.TpTagView;
import JinRyuu.JRMCore.TpUtil;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagString;

/** Testes da fachada TpUtil com o NBT stub (mesma API SRG do jogo). */
public final class TpUtilTest {

    private final Harness h;

    public TpUtilTest(Harness h) {
        this.h = h;
    }

    public void run() {
        h.section("TpUtil (NBT real por API, estado separado)");

        EntityPlayer p1 = new EntityPlayer();
        EntityPlayer p2 = new EntityPlayer();

        // Escrita de NBT NAO pode alterar o estado global.
        JinRyuu.JRMCore.JRMCoreH.curTPLong = 111L;
        JinRyuu.JRMCore.JRMCoreH.curTP = 222;
        TpUtil.write(p1, 3000000000L);
        h.eq("write player long", TpUtil.getTP(p1), 3000000000L);
        h.eq("write nao altera curTPLong", JinRyuu.JRMCore.JRMCoreH.curTPLong, 111L);
        h.eq("write nao altera curTP", JinRyuu.JRMCore.JRMCoreH.curTP, 222);

        TpUtil.write(p2, 7L);
        h.eq("dois jogadores p1", TpUtil.getTP(p1), 3000000000L);
        h.eq("dois jogadores p2", TpUtil.getTP(p2), 7L);
        TpUtil.write(p2, 9000000000L);
        h.eq("dois jogadores p1 intacto", TpUtil.getTP(p1), 3000000000L);
        h.eq("dois jogadores p2 novo", TpUtil.getTP(p2), 9000000000L);

        h.eq("getTPInt satura", TpUtil.getTPInt(p1), Integer.MAX_VALUE);
        h.eq("getTPInt pequeno", TpUtil.getTPInt(p2), Integer.MAX_VALUE);

        // Migracao via fachada
        EntityPlayer legacy = new EntityPlayer();
        legacy.pres.func_74768_a(TpNbtCore.KEY_INT, 12345);
        h.eq("legado via fachada", TpUtil.getTP(legacy), 12345L);
        h.eq("legado migrado", legacy.pres.rawType(TpNbtCore.KEY_LONG),
                TpTagView.TAG_LONG);

        // Tipo incorreto: valor 0 e save intacto
        EntityPlayer bad = new EntityPlayer();
        bad.pres.putRaw(TpNbtCore.KEY_LONG, new NBTTagString("corrompido"));
        h.eq("tipo incorreto valor", TpUtil.getTP(bad), 0L);
        h.eq("tipo incorreto preservado", bad.pres.rawType(TpNbtCore.KEY_LONG), 8);
        h.eq("tipo incorreto nao cria int", bad.pres.rawType(TpNbtCore.KEY_INT), -1);

        // Ganho
        TpUtil.write(p1, 3000000000L);
        TpLogic.Result g = TpUtil.gain(p1, 1000000000000L);
        h.truth("ganho ok", g.ok());
        h.eq("ganho 1e12 aplicado", TpUtil.getTP(p1), 1003000000000L);
        h.eq("ganho negativo rejeitado", TpUtil.gain(p1, -1L).status(),
                TpLogic.Status.NEGATIVE_AMOUNT);
        h.eq("ganho negativo nao altera", TpUtil.getTP(p1), 1003000000000L);

        // Overflow rejeitado
        TpUtil.write(p1, Long.MAX_VALUE);
        h.eq("overflow rejeitado", TpUtil.gain(p1, 1L).status(), TpLogic.Status.OVERFLOW);
        h.eq("overflow nao altera", TpUtil.getTP(p1), Long.MAX_VALUE);

        // Gasto parcial, exato e excessivo acima de INT_MAX
        TpUtil.write(p1, 5000000000L);
        h.eq("gasto parcial valor", TpUtil.spend(p1, 3000000000L).value(), 2000000000L);
        h.eq("gasto parcial persistido", TpUtil.getTP(p1), 2000000000L);
        h.eq("gasto exato valor", TpUtil.spend(p1, 2000000000L).value(), 0L);
        h.eq("gasto exato persistido", TpUtil.getTP(p1), 0L);
        TpUtil.write(p1, 5000000000L);
        h.eq("gasto excessivo status", TpUtil.spend(p1, 5000000001L).status(),
                TpLogic.Status.INSUFFICIENT);
        h.eq("gasto excessivo nao altera", TpUtil.getTP(p1), 5000000000L);
        h.eq("gasto negativo status", TpUtil.spend(p1, -3L).status(),
                TpLogic.Status.NEGATIVE_AMOUNT);

        // Estado local x rede
        TpUtil.applyLocal(4000000000L);
        h.eq("applyLocal curTPLong", JinRyuu.JRMCore.JRMCoreH.curTPLong, 4000000000L);
        h.eq("applyLocal espelho", JinRyuu.JRMCore.JRMCoreH.curTP, Integer.MAX_VALUE);
        TpUtil.applyLocal(123L);
        h.eq("applyLocal pequeno espelho", JinRyuu.JRMCore.JRMCoreH.curTP, 123);
        TpUtil.acceptNetwork(6000000000L);
        h.eq("acceptNetwork curTPLong", JinRyuu.JRMCore.JRMCoreH.curTPLong, 6000000000L);
        h.eq("acceptNetwork espelho", JinRyuu.JRMCore.JRMCoreH.curTP, Integer.MAX_VALUE);

        boolean threw = false;
        try {
            TpUtil.applyLocal(-1L);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        h.truth("applyLocal negativo lanca", threw);
        h.eq("applyLocal negativo nao altera", JinRyuu.JRMCore.JRMCoreH.curTPLong,
                6000000000L);

        threw = false;
        try {
            TpUtil.acceptNetwork(-1L);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        h.truth("acceptNetwork negativo lanca", threw);

        threw = false;
        try {
            TpUtil.write(p1, -1L);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        h.truth("write absoluto negativo lanca", threw);
        h.eq("write negativo nao altera", TpUtil.getTP(p1), 5000000000L);

        // Escrita absoluta Long.MAX_VALUE e 1 trilhao
        TpUtil.write(p1, 1000000000000L);
        h.eq("write 1e12", TpUtil.getTP(p1), 1000000000000L);
        TpUtil.write(p1, Long.MAX_VALUE);
        h.eq("write Long.MAX", TpUtil.getTP(p1), Long.MAX_VALUE);
        h.eq("espelho Long.MAX", p1.pres.func_74762_e(TpNbtCore.KEY_INT),
                Integer.MAX_VALUE);

        // Inspecao de tipo exposta
        h.eq("inspect status", TpUtil.inspect(p1.pres).status(), TpNbtCore.Status.LONG);
        h.eq("inspect tipo", TpUtil.inspect(p1.pres).tagId(), 4);
    }
}
