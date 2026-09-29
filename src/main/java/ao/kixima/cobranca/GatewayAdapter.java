package ao.kixima.cobranca;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * O que cada canal de pagamento automático sabe fazer (multicaixaService.js,
 * paypayService.js, bancoGatewayService.js). Todos RECUSAM-SE A FINGIR: sem
 * credenciais reais, {@link #pedirPagamento} e {@link #confirmarCallback}
 * lançam com a lista exata do que falta — nunca devolvem sucesso simulado.
 */
public interface GatewayAdapter {

    /** O que o adaptador apurou JUNTO DO GATEWAY sobre uma transação — nunca o que o callback afirmava. */
    record Verificacao(String idTransacao, boolean pago, BigDecimal montante, String referencia, String origem) {
    }

    record PedidoPagamento(String referencia, BigDecimal valor, String moeda, String telemovel) {
    }

    String canal();

    List<String> emFalta();

    default boolean disponivel() {
        return emFalta().isEmpty();
    }

    Map<String, Object> estado();

    /** Pede a iniciação de um pagamento; devolve a resposta do gateway (com `id`/`transactionId`). */
    Map<String, Object> pedirPagamento(PedidoPagamento pedido);

    /** Confere um aviso de pagamento — volta sempre a perguntar ao gateway pela transação. */
    Verificacao confirmarCallback(Map<String, Object> payload);
}
