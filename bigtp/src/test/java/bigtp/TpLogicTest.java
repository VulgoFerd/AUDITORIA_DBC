package bigtp;

import JinRyuu.JRMCore.TpLogic;

/** Testes puros de TpLogic. */
public final class TpLogicTest {

    private final Harness h;

    public TpLogicTest(Harness h) {
        this.h = h;
    }

    public void run() {
        h.section("TpLogic");

        h.eq("absolute 0", TpLogic.absolute(0L).value(), 0L);
        h.eq("absolute 1", TpLogic.absolute(1L).value(), 1L);
        h.eq("absolute INT_MAX", TpLogic.absolute(Integer.MAX_VALUE).value(),
                (long) Integer.MAX_VALUE);
        h.eq("absolute INT_MAX+1", TpLogic.absolute((long) Integer.MAX_VALUE + 1L).value(),
                (long) Integer.MAX_VALUE + 1L);
        h.eq("absolute 3e9", TpLogic.absolute(3000000000L).value(), 3000000000L);
        h.eq("absolute 1e10", TpLogic.absolute(10000000000L).value(), 10000000000L);
        h.eq("absolute 1e12", TpLogic.absolute(1000000000000L).value(), 1000000000000L);
        h.eq("absolute Long.MAX", TpLogic.absolute(Long.MAX_VALUE).value(), Long.MAX_VALUE);
        h.eq("absolute negativo", TpLogic.absolute(-1L).status(), TpLogic.Status.NEGATIVE_VALUE);
        h.eq("absolute negativo nao muda", TpLogic.absolute(-5L).value(), 0L);

        h.eq("gain 0", TpLogic.gain(0L, 0L).value(), 0L);
        h.eq("gain 1", TpLogic.gain(0L, 1L).value(), 1L);
        h.eq("gain sobre INT_MAX", TpLogic.gain((long) Integer.MAX_VALUE, 1L).value(),
                (long) Integer.MAX_VALUE + 1L);
        h.eq("gain 1e12", TpLogic.gain(0L, 1000000000000L).value(), 1000000000000L);
        h.eq("gain negativo", TpLogic.gain(10L, -1L).status(), TpLogic.Status.NEGATIVE_AMOUNT);
        h.eq("gain overflow status", TpLogic.gain(Long.MAX_VALUE, 1L).status(),
                TpLogic.Status.OVERFLOW);
        h.eq("gain overflow nao altera", TpLogic.gain(Long.MAX_VALUE, 1L).value(), Long.MAX_VALUE);
        h.eq("gain overflow delta", TpLogic.gain(Long.MAX_VALUE, 1L).delta(), 1L);

        h.eq("spend parcial", TpLogic.spend(100L, 40L).value(), 60L);
        h.eq("spend exato", TpLogic.spend(100L, 100L).value(), 0L);
        h.eq("spend excessivo status", TpLogic.spend(100L, 101L).status(),
                TpLogic.Status.INSUFFICIENT);
        h.eq("spend excessivo nao altera", TpLogic.spend(100L, 101L).value(), 100L);
        h.eq("spend negativo", TpLogic.spend(100L, -1L).status(),
                TpLogic.Status.NEGATIVE_AMOUNT);
        h.eq("spend acima de INT_MAX", TpLogic.spend(5000000000L, 3000000000L).value(),
                2000000000L);
        h.eq("spend 1e12 parcial", TpLogic.spend(1000000000000L, 1L).value(),
                999999999999L);

        h.eq("mirror 0", TpLogic.mirror(0L), 0);
        h.eq("mirror 1", TpLogic.mirror(1L), 1);
        h.eq("mirror INT_MAX", TpLogic.mirror(Integer.MAX_VALUE), Integer.MAX_VALUE);
        h.eq("mirror INT_MAX+1", TpLogic.mirror((long) Integer.MAX_VALUE + 1L),
                Integer.MAX_VALUE);
        h.eq("mirror 1e12", TpLogic.mirror(1000000000000L), Integer.MAX_VALUE);
        h.eq("mirror Long.MAX", TpLogic.mirror(Long.MAX_VALUE), Integer.MAX_VALUE);
        h.eq("mirror negativo", TpLogic.mirror(-5L), 0);

        h.eq("delta positivo", TpLogic.applyDelta(10L, 5L).value(), 15L);
        h.eq("delta negativo", TpLogic.applyDelta(10L, -5L).value(), 5L);
        h.eq("delta excessivo", TpLogic.applyDelta(10L, -15L).status(),
                TpLogic.Status.INSUFFICIENT);
        h.eq("delta Long.MIN", TpLogic.applyDelta(10L, Long.MIN_VALUE).status(),
                TpLogic.Status.INSUFFICIENT);
    }
}
