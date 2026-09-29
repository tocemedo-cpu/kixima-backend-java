package ao.kixima.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Espelha backend/src/services/aiRecommendationService.js — recomendação
 * curta, em português, a partir dos dados REAIS de compra. Mesmo princípio
 * "recusa-se a fingir": sem ANTHROPIC_API_KEY o texto vem null com o motivo
 * explícito; nunca um texto de IA fabricado. Chamada direta à Messages API
 * (sem SDK), com o mesmo modelo, max_tokens e esforço que o Node.
 */
@Service
public class AiRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(AiRecommendationService.class);

    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public AiRecommendationService(@Value("${kixima.anthropic.api-key:}") String apiKey,
                                   @Value("${kixima.anthropic.model:claude-sonnet-5}") String model, ObjectMapper objectMapper) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.objectMapper = objectMapper;
    }

    public boolean disponivel() {
        return !apiKey.isBlank();
    }

    private static String f2(Object v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v == null ? 0d : ((Number) v).doubleValue());
    }

    static String construirPrompt(String empresa, double volumeAtual, List<Map<String, Object>> categorias,
                                  Map<String, Object> thresholdInfo, List<Map<String, Object>> oportunidades) {
        List<String> linhasCategorias = new ArrayList<>();
        for (Map<String, Object> c : categorias.subList(0, Math.min(8, categorias.size()))) {
            linhasCategorias.add("- " + c.get("categoria") + ": " + f2(c.get("valor")) + " USD (" + c.get("percentual") + "% do total)");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> proximo = (Map<String, Object>) thresholdInfo.get("proximoThreshold");
        String linhaThreshold = proximo != null
                ? "Próximo patamar: " + proximo.get("minVolumeUsd") + " USD (desconto de " + proximo.get("discountPercent") + "%). Faltam "
                + thresholdInfo.get("faltamUsd") + " USD. Poupança adicional potencial ao atingir: " + thresholdInfo.get("poupancaPotencialUsd") + " USD."
                : "Já está no maior patamar de desconto ativo.";
        List<String> linhasOportunidades = new ArrayList<>();
        for (Map<String, Object> o : oportunidades.subList(0, Math.min(5, oportunidades.size()))) {
            linhasOportunidades.add("- " + o.get("categoria") + ": " + o.get("numeroPos") + " ordens separadas, faltam " + o.get("faltamUsd")
                    + " USD para o desconto de " + o.get("descontoPotencial") + "%");
        }
        return "És um analista de compras B2B. Com base nestes dados REAIS de compra da empresa \"" + empresa
                + "\" nos últimos 12 meses, escreve uma recomendação curta (máximo 120 palavras, em português, tom direto e prático) "
                + "sobre como aproveitar a economia de escala.\n\n"
                + "Volume total: " + f2(volumeAtual) + " USD\n"
                + "Desconto atual: " + thresholdInfo.get("descontoAtual") + "%\n"
                + linhaThreshold + "\n\n"
                + "Categorias com mais volume:\n" + (linhasCategorias.isEmpty() ? "Sem compras no período." : String.join("\n", linhasCategorias)) + "\n\n"
                + "Oportunidades de consolidação (compras fragmentadas que poderiam cruzar um patamar se juntas):\n"
                + (linhasOportunidades.isEmpty() ? "Nenhuma identificada no período." : String.join("\n", linhasOportunidades)) + "\n\n"
                + "Não inventes números que não estejam aqui. Se não houver nada de acionável, diz isso claramente em vez de forçar uma recomendação.";
    }

    private static Map<String, Object> resposta(String texto, String motivo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("texto", texto);
        m.put("motivo", motivo);
        return m;
    }

    /** Nunca lança — devolve `{ texto, motivo }`. */
    public Map<String, Object> gerar(String empresa, double volumeAtual, List<Map<String, Object>> categorias,
                                     Map<String, Object> thresholdInfo, List<Map<String, Object>> oportunidades) {
        if (!disponivel()) return resposta(null, "ANTHROPIC_API_KEY não está configurada — recomendação de IA desativada.");
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("max_tokens", 400);
            payload.put("output_config", Map.of("effort", "low"));
            payload.put("messages", List.of(Map.of("role", "user", "content", construirPrompt(empresa, volumeAtual, categorias, thresholdInfo, oportunidades))));
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                    .timeout(Duration.ofSeconds(60))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) throw new IllegalStateException("HTTP " + resp.statusCode());
            JsonNode content = objectMapper.readTree(resp.body()).path("content");
            String texto = null;
            for (JsonNode bloco : content) {
                if ("text".equals(bloco.path("type").asText())) {
                    texto = bloco.path("text").asText("").trim();
                    break;
                }
            }
            return resposta(texto == null || texto.isEmpty() ? null : texto, texto == null || texto.isEmpty() ? "A API não devolveu texto." : null);
        } catch (Exception err) {
            log.warn("aiRecommendationService: falha ao chamar a API da Claude: {}", err.getMessage());
            return resposta(null, "Falha ao contactar o serviço de IA — tenta novamente mais tarde.");
        }
    }
}
