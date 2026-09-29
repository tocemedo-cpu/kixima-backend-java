package ao.kixima.erp;

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
import java.util.Map;

/**
 * Espelha {@code callIntegration} de erpConfigService.js — cliente HTTP
 * para o microserviço kixima-integration-service (fonte de verdade em
 * runtime das credenciais ERP). Opcional: sem {@code INTEGRATION_URL}/
 * {@code INTEGRATION_ADMIN_TOKEN}, todas as chamadas voltam
 * {@code skipped=true}, nunca lançam — a configuração fica guardada em
 * {@link CompanyErpConfig} mesmo sem o microserviço disponível.
 */
@Service
public class ErpIntegrationClient {

    private static final Logger log = LoggerFactory.getLogger(ErpIntegrationClient.class);

    private final String baseUrl;
    private final String token;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public ErpIntegrationClient(@Value("${kixima.erp.integration-url:}") String baseUrl,
                                 @Value("${kixima.erp.integration-admin-token:}") String token,
                                 ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.objectMapper = objectMapper;
    }

    public record Resultado(boolean skipped, boolean ok, Integer status, JsonNode data, String message) {
        static Resultado skipped(String message) {
            return new Resultado(true, false, null, null, message);
        }
    }

    public Resultado chamar(String metodo, String caminho, Map<String, Object> corpo) {
        if (baseUrl.isBlank() || token.isBlank()) {
            return Resultado.skipped("Microserviço de integração não configurado (INTEGRATION_URL/TOKEN).");
        }
        try {
            String base = baseUrl.replaceAll("/$", "");
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + caminho))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json");
            HttpRequest req = switch (metodo) {
                case "DELETE" -> builder.DELETE().build();
                case "POST" -> builder.POST(corpoHttp(corpo)).build();
                case "PUT" -> builder.PUT(corpoHttp(corpo)).build();
                default -> builder.GET().build();
            };
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode data = null;
            try {
                data = resp.body() == null || resp.body().isBlank() ? null : objectMapper.readTree(resp.body());
            } catch (Exception ignored) {
                // corpo não-JSON — data fica null, tal como o `.catch(() => null)` do Node.
            }
            boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 300;
            return new Resultado(false, ok, resp.statusCode(), data, null);
        } catch (Exception e) {
            log.warn("erpConfig: falha a contactar o microserviço de integração ({} {}): {}", metodo, caminho, e.getMessage());
            return new Resultado(false, false, null, null, e.getMessage());
        }
    }

    private HttpRequest.BodyPublisher corpoHttp(Map<String, Object> corpo) {
        if (corpo == null) return HttpRequest.BodyPublishers.noBody();
        try {
            return HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(corpo), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Falha a serializar pedido ao microserviço de integração.", e);
        }
    }
}
