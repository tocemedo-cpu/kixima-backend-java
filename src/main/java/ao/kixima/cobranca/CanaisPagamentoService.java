package ao.kixima.cobranca;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Espelha canaisPagamentoService.js — o ponto único que mapeia um
 * CanalCobranca ao adaptador que fala com esse gateway, para o serviço de
 * subscrição, a rota de webhook e o painel de prontidão nunca divergirem.
 */
@Service
public class CanaisPagamentoService {

    /** Todos os canais automáticos — sem a transferência manual, que não passa por adaptador. */
    public static final List<CanalCobranca> CANAIS_GATEWAY = List.of(CanalCobranca.EMIS_MULTICAIXA, CanalCobranca.PAYPAY,
            CanalCobranca.BAI, CanalCobranca.BFA, CanalCobranca.STANDARD_BANK_ANGOLA);

    private final Map<CanalCobranca, GatewayAdapter> adaptadores = new LinkedHashMap<>();

    public CanaisPagamentoService(
            @Value("${kixima.emis.base-url:}") String emisBaseUrl, @Value("${kixima.emis.pos-id:}") String emisPosId,
            @Value("${kixima.emis.token:}") String emisToken, @Value("${kixima.emis.callback-url:}") String emisCallbackUrl,
            @Value("${kixima.paypay.base-url:}") String paypayBaseUrl, @Value("${kixima.paypay.merchant-id:}") String paypayMerchantId,
            @Value("${kixima.paypay.token:}") String paypayToken, @Value("${kixima.paypay.callback-url:}") String paypayCallbackUrl,
            @Value("${kixima.bancos.bai.base-url:}") String baiBaseUrl, @Value("${kixima.bancos.bai.client-id:}") String baiClientId,
            @Value("${kixima.bancos.bai.client-secret:}") String baiClientSecret, @Value("${kixima.bancos.bai.callback-url:}") String baiCallbackUrl,
            @Value("${kixima.bancos.bfa.base-url:}") String bfaBaseUrl, @Value("${kixima.bancos.bfa.client-id:}") String bfaClientId,
            @Value("${kixima.bancos.bfa.client-secret:}") String bfaClientSecret, @Value("${kixima.bancos.bfa.callback-url:}") String bfaCallbackUrl,
            @Value("${kixima.bancos.standard-bank-ao.base-url:}") String sbaBaseUrl, @Value("${kixima.bancos.standard-bank-ao.client-id:}") String sbaClientId,
            @Value("${kixima.bancos.standard-bank-ao.client-secret:}") String sbaClientSecret, @Value("${kixima.bancos.standard-bank-ao.callback-url:}") String sbaCallbackUrl) {

        // EMIS Multicaixa Express — implementado contra a especificação, por ligar (multicaixaService.js).
        adaptadores.put(CanalCobranca.EMIS_MULTICAIXA, new HttpGatewayAdapter(
                new HttpGatewayAdapter.Definicao("EMIS_MULTICAIXA", "Multicaixa Express", "MULTICAIXA_EXPRESS", "/online-payment",
                        "/transactions/", Set.of("ACCEPTED"), "EMIS",
                        "Implementado contra a especificação da EMIS, por ligar. Requer contrato e credenciais.",
                        "A EMIS recusou o pedido ({status}). A fatura NÃO foi paga.", "AOA"),
                emisBaseUrl, emisToken,
                List.of(new HttpGatewayAdapter.Credencial("EMIS_BASE_URL", "baseUrl", emisBaseUrl), new HttpGatewayAdapter.Credencial("EMIS_POS_ID", "posId", emisPosId),
                        new HttpGatewayAdapter.Credencial("EMIS_TOKEN", "token", emisToken), new HttpGatewayAdapter.Credencial("EMIS_CALLBACK_URL", "callbackUrl", emisCallbackUrl)),
                corpo -> {
                    corpo.put("token", emisToken);
                    if (corpo.containsKey("phone")) corpo.put("mobile", corpo.remove("phone"));
                    corpo.put("callbackUrl", emisCallbackUrl);
                    corpo.put("posID", emisPosId);
                }));

        // PayPay — forma provável, por confirmar contra a documentação real (paypayService.js).
        adaptadores.put(CanalCobranca.PAYPAY, new HttpGatewayAdapter(
                new HttpGatewayAdapter.Definicao("PAYPAY", "PayPay", "PAYPAY", "/payments", "/payments/", Set.of("PAID", "COMPLETED"), "PAYPAY",
                        "Forma provável, por confirmar contra a documentação real da PayPay. Requer contrato e credenciais.",
                        "A PayPay recusou o pedido ({status}). O documento NÃO foi pago.", null),
                paypayBaseUrl, paypayToken,
                List.of(new HttpGatewayAdapter.Credencial("PAYPAY_BASE_URL", "baseUrl", paypayBaseUrl), new HttpGatewayAdapter.Credencial("PAYPAY_MERCHANT_ID", "merchantId", paypayMerchantId),
                        new HttpGatewayAdapter.Credencial("PAYPAY_TOKEN", "token", paypayToken), new HttpGatewayAdapter.Credencial("PAYPAY_CALLBACK_URL", "callbackUrl", paypayCallbackUrl)),
                corpo -> {
                    corpo.put("merchantId", paypayMerchantId);
                    corpo.put("callbackUrl", paypayCallbackUrl);
                }));

        // Bancos — UM adaptador genérico (bancoGatewayService.js): inventar três seria fingir conhecimento que não existe.
        banco(CanalCobranca.BAI, "BAI", "BAI", baiBaseUrl, baiClientId, baiClientSecret, baiCallbackUrl);
        banco(CanalCobranca.BFA, "BFA", "BFA", bfaBaseUrl, bfaClientId, bfaClientSecret, bfaCallbackUrl);
        banco(CanalCobranca.STANDARD_BANK_ANGOLA, "Standard Bank Angola", "STANDARD_BANK_AO", sbaBaseUrl, sbaClientId, sbaClientSecret, sbaCallbackUrl);
    }

    private void banco(CanalCobranca canal, String nome, String envPrefix, String baseUrl, String clientId, String clientSecret, String callbackUrl) {
        adaptadores.put(canal, new HttpGatewayAdapter(
                new HttpGatewayAdapter.Definicao(canal.name(), nome, canal.name(), "/payments", "/payments/", Set.of("PAID", "COMPLETED"), nome,
                        "Forma provável, por confirmar contra a documentação real da API do " + nome + ". Requer contrato e credenciais.",
                        "O " + nome + " recusou o pedido ({status}). O documento NÃO foi pago.", null),
                baseUrl, clientSecret,
                List.of(new HttpGatewayAdapter.Credencial(envPrefix + "_BASE_URL", "baseUrl", baseUrl), new HttpGatewayAdapter.Credencial(envPrefix + "_CLIENT_ID", "clientId", clientId),
                        new HttpGatewayAdapter.Credencial(envPrefix + "_CLIENT_SECRET", "clientSecret", clientSecret), new HttpGatewayAdapter.Credencial(envPrefix + "_CALLBACK_URL", "callbackUrl", callbackUrl)),
                corpo -> {
                    corpo.remove("phone");
                    corpo.put("clientId", clientId);
                    corpo.put("callbackUrl", callbackUrl);
                }));
    }

    /** O adaptador para um canal — ou null para TRANSFERENCIA_MANUAL/desconhecido. */
    public GatewayAdapter adaptador(CanalCobranca canal) {
        return canal == null ? null : adaptadores.get(canal);
    }

    /** Aceita o nome do canal em qualquer capitalização (a rota de webhook recebe-o no caminho). */
    public GatewayAdapter adaptador(String canal) {
        if (canal == null) return null;
        try {
            return adaptador(CanalCobranca.valueOf(canal.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** O estado de todos os canais automáticos, para o painel de prontidão. */
    public Map<String, Object> estados() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (CanalCobranca canal : CANAIS_GATEWAY) m.put(canal.name(), adaptadores.get(canal).estado());
        return m;
    }
}
