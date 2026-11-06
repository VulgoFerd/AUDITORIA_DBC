package JinRyuu.JRMCore;

/**
 * Unico ponto autorizado a alterar o estado global do cliente
 * (JRMCoreH.curTPLong e o espelho JRMCoreH.curTP).
 *
 * Leituras e escritas de NBT NAO passam por aqui.
 */
public final class TpClientState {

    private TpClientState() {
    }

    /**
     * Aplica um saldo absoluto ao estado local do cliente.
     * Valor negativo e rejeitado com IllegalArgumentException.
     */
    public static void apply(long value) {
        TpLogic.Result result = TpLogic.absolute(value);
        if (!result.ok()) {
            throw new IllegalArgumentException("TP local invalido: " + value
                    + " (" + result.status() + ")");
        }
        JRMCoreH.curTPLong = result.value();
        JRMCoreH.curTP = TpLogic.mirror(result.value());
    }

    public static long current() {
        return JRMCoreH.curTPLong;
    }

    public static int currentMirror() {
        return JRMCoreH.curTP;
    }
}
