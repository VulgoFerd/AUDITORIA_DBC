package JinRyuu.JRMCore;

/**
 * Entrada de estado vindo da rede.
 *
 * Separado de {@link TpClientState} para que a origem (pacote) seja explicita.
 * Nao le nem grava NBT.
 */
public final class TpNetwork {

    private TpNetwork() {
    }

    /**
     * Aceita um saldo recebido do servidor.
     * Valor negativo e rejeitado com IllegalArgumentException.
     */
    public static void accept(long value) {
        TpLogic.Result result = TpLogic.absolute(value);
        if (!result.ok()) {
            throw new IllegalArgumentException("TP recebido pela rede invalido: "
                    + value + " (" + result.status() + ")");
        }
        TpClientState.apply(result.value());
    }
}
