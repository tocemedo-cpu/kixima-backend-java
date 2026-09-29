package ao.kixima.integration;

import ao.kixima.common.error.ErrorResponse;
import ao.kixima.erp.ErpSyncDirection;
import ao.kixima.erp.ErpSyncLog;
import ao.kixima.erp.ErpSyncLogRepository;
import ao.kixima.erp.ErpSyncStatus;
import ao.kixima.po.PoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Espelha integrationRoutes.js — endpoint de retorno (callback) do
 * microserviço de integração ERP (kixima-integration-service). Público,
 * protegido pela assinatura HMAC-SHA256 do corpo bruto no cabeçalho
 * {@code X-Kixima-Signature}; falha fechada sem {@code KIXIMA_CALLBACK_SECRET}.
 */
@RestController
@RequestMapping("/api/integration")
public class IntegrationCallbackController {

    private static final Logger log = LoggerFactory.getLogger(IntegrationCallbackController.class);

    private final PoService poService;
    private final ErpSyncLogRepository erpSyncLogRepository;
    private final ObjectMapper objectMapper;
    private final String secret;

    public IntegrationCallbackController(PoService poService, ErpSyncLogRepository erpSyncLogRepository, ObjectMapper objectMapper,
                                         @Value("${kixima.integration.callback-secret:}") String secret) {
        this.poService = poService;
        this.erpSyncLogRepository = erpSyncLogRepository;
        this.objectMapper = objectMapper;
        this.secret = secret == null ? "" : secret;
    }

    static String hmacHex(String secret, String raw) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 indisponível.", e);
        }
    }

    /**
     * Regista uma falha de sincronização quando se conhece a PO — o registo em
     * si só depende do FK, por isso falha silenciosamente para uma PO inexistente.
     */
    private void registarFalha(String poId, String eventType, String mensagem) {
        if (poId == null || poId.isBlank()) return;
        try {
            erpSyncLogRepository.save(new ErpSyncLog(poId, ErpSyncDirection.INBOUND,
                    eventType == null || eventType.isBlank() ? "desconhecido" : eventType, ErpSyncStatus.FAILED, null, mensagem));
        } catch (Exception ignorado) {
            // não há onde pendurar o log de uma PO que não existe
        }
    }

    private static String texto(JsonNode n, String campo) {
        JsonNode v = n == null ? null : n.get(campo);
        return v == null || v.isNull() ? null : v.asText();
    }

    @PostMapping("/callback")
    public ResponseEntity<?> callback(@RequestBody(required = false) String raw,
                                      @RequestHeader(value = "X-Kixima-Signature", required = false) String signature) {
        // Falha fechada: sem segredo configurado, o endpoint não aceita nada.
        if (secret.isBlank()) {
            log.warn("Integração ERP: callback recebido mas KIXIMA_CALLBACK_SECRET não está definido — recusado.");
            return ResponseEntity.status(503).body(ErrorResponse.of("CALLBACK_NOT_CONFIGURED", "Callback de integração não configurado.", null));
        }

        String corpo = raw == null || raw.isEmpty() ? "{}" : raw;
        String expected = hmacHex(secret, corpo);
        byte[] a = (signature == null ? "" : signature).getBytes(StandardCharsets.UTF_8);
        byte[] b = expected.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length || !MessageDigest.isEqual(a, b)) {
            return ResponseEntity.status(401).body(ErrorResponse.of("INVALID_SIGNATURE", "Assinatura inválida.", null));
        }

        JsonNode body;
        try {
            body = objectMapper.readTree(corpo);
        } catch (Exception e) {
            body = objectMapper.createObjectNode();
        }
        // Não registar o payload completo (pode conter dados de negócio). Só o tipo.
        String type = texto(body, "type");
        JsonNode data = body == null ? null : body.get("data");
        log.info("Integração ERP: callback recebido (type={})", type);

        try {
            switch (type == null ? "" : type) {
                case "purchase_order.approval_decided" -> {
                    String poId = texto(data, "poId");
                    JsonNode aprovado = data == null ? null : data.get("aprovado");
                    if (poId == null || poId.isBlank() || aprovado == null || !aprovado.isBoolean()) {
                        throw new IllegalArgumentException("Payload inválido: \"poId\" e \"aprovado\" (boolean) são obrigatórios.");
                    }
                    poService.aplicarDecisaoErp(poId, aprovado.asBoolean(), texto(data, "erpExternalId"), texto(data, "motivo"));
                }
                case "payment.confirmed" -> {
                    String poId = texto(data, "poId");
                    if (poId == null || poId.isBlank()) throw new IllegalArgumentException("Payload inválido: \"poId\" é obrigatório.");
                    JsonNode valor = data.get("valorPago");
                    BigDecimal valorPago = valor == null || valor.isNull() ? null : new BigDecimal(valor.asText());
                    String pagoEm = texto(data, "pagoEm");
                    poService.aplicarPagamentoErp(poId, texto(data, "erpExternalId"), valorPago,
                            pagoEm == null || pagoEm.isBlank() ? null : Instant.parse(pagoEm));
                }
                default -> log.warn("Integração ERP: tipo de callback desconhecido (type={})", type);
            }
            return ResponseEntity.ok(Map.of("received", true));
        } catch (Exception err) {
            // O erro é de NEGÓCIO (PO errada, estado que já não aceita a decisão, payload
            // malformado) — nunca de transporte. Responder 4xx/5xx só faria o microserviço
            // repetir um pedido que nunca vai ter sucesso; regista-se a falha e confirma-se
            // a receção, mesmo assim.
            log.warn("Integração ERP: callback com erro de negócio (type={}): {}", type, err.getMessage());
            registarFalha(texto(data, "poId"), type, err.getMessage());
            Map<String, Object> resposta = new LinkedHashMap<>();
            resposta.put("received", true);
            resposta.put("error", err.getMessage());
            return ResponseEntity.ok(resposta);
        }
    }
}
