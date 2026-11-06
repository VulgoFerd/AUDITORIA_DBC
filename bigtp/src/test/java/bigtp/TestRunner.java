package bigtp;

/** Executa todos os testes executaveis fora do jogo. */
public final class TestRunner {

    private TestRunner() {
    }

    public static void main(String[] args) {
        Harness h = new Harness();

        new TpLogicTest(h).run();
        new TpNbtTest(h).run();
        new TpUtilTest(h).run();

        h.section("Testes que exigem runtime do Minecraft (NAO EXECUTADOS aqui)");
        System.out.println("  NAO EXECUTADO  /jrmctp no jogo (parse/absolute/gain/remove/persistencia/sync)");
        System.out.println("  NAO EXECUTADO  logout/login com save real");
        System.out.println("  NAO EXECUTADO  sincronizacao cliente/servidor em partida real");
        System.out.println("  NAO EXECUTADO  GUI do DBC e NPCDBC com saldo real");
        System.out.println("  NAO EXECUTADO  NPCDBC DBCData em mundo real");

        System.exit(h.summary());
    }
}
