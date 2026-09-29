package ao.kixima.conciliacao;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Espelha só {@code estado()}/{@code disponivel()}/{@code emFalta()} de
 * backend/src/services/multicaixaService.js — o estado do canal EMIS
 * Multicaixa Express, para o painel o mostrar em vez de a ausência ser
 * descoberta por quem carrega no botão.
 *
 * NÃO PORTADO: {@code pedirPagamento}/{@code confirmarCallback} (o adaptador
 * do gateway em si, e os irmãos PayPay/bancos) — pertencem à cobrança de
 * subscrições (grupo C das lacunas), com o webhook {@code /api/webhooks/pagamento}.
 * Como no Node, RECUSA-SE A FINGIR: sem credenciais reais o canal não existe.
 */
@Service
public class MulticaixaService {

    public static final String CANAL = "MULTICAIXA_EXPRESS";

    private final String baseUrl;
    private final String posId;
    private final String token;
    private final String callbackUrl;

    public MulticaixaService(@Value("${kixima.emis.base-url:}") String baseUrl,
                             @Value("${kixima.emis.pos-id:}") String posId,
                             @Value("${kixima.emis.token:}") String token,
                             @Value("${kixima.emis.callback-url:}") String callbackUrl) {
        this.baseUrl = baseUrl;
        this.posId = posId;
        this.token = token;
        this.callbackUrl = callbackUrl;
    }

    public List<String> emFalta() {
        List<String> falta = new ArrayList<>();
        // As chaves do CONFIG do multicaixaService.js (não os nomes das variáveis de ambiente) — é o que o painel mostra.
        if (baseUrl == null || baseUrl.isBlank()) falta.add("baseUrl");
        if (posId == null || posId.isBlank()) falta.add("posId");
        if (token == null || token.isBlank()) falta.add("token");
        if (callbackUrl == null || callbackUrl.isBlank()) falta.add("callbackUrl");
        return falta;
    }

    public boolean disponivel() {
        return emFalta().isEmpty();
    }

    public Map<String, Object> estado() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("canal", CANAL);
        m.put("disponivel", disponivel());
        m.put("emFalta", emFalta());
        m.put("nota", disponivel() ? "Configurado."
                : "Implementado contra a especificação da EMIS, por ligar. Requer contrato e credenciais.");
        return m;
    }
}
