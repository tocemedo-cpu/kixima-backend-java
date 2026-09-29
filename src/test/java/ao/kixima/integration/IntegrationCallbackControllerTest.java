package ao.kixima.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Espelha tests/erp-doa-approval.test.js — ERP DOA Approval (PRO): quando o
 * comprador tem ERP real configurado, a aprovação e o pagamento da PO
 * acontecem no ERP e chegam pelo callback assinado (HMAC-SHA256).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class IntegrationCallbackControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPANY_ADMIN_EMAIL = "admin@petroangola.co.ao";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String FORNECEDOR_EMAIL = "fornecedor@kianda.co.ao";
    private static final String ADMIN_SISTEMA_EMAIL = "admin@kixima.co.ao";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${kixima.integration.callback-secret}")
    private String secret;

    private String login(String email) throws Exception {
        var res = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    private ResultActions callback(Object body, String assinaturaForcada) throws Exception {
        String raw = objectMapper.writeValueAsString(body);
        String sig = assinaturaForcada != null ? assinaturaForcada : IntegrationCallbackController.hmacHex(secret, raw);
        return mockMvc.perform(post("/api/integration/callback")
                .contentType("application/json")
                .header("X-Kixima-Signature", sig)
                .content(raw));
    }

    private ResultActions callback(Object body) throws Exception {
        return callback(body, null);
    }

    private Map<String, Object> evento(String type, Map<String, Object> data) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        m.put("data", data);
        return m;
    }

    private Map<String, Object> dados(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** PRO + SAP real configurado na compradora do seed. */
    private String configurarErpReal(String adminToken) throws Exception {
        String compradoraId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = 'AO-CLI-0001'", String.class);
        jdbcTemplate.update("UPDATE companies SET plan = 'PRO', search_rank = 2 WHERE id = ?", compradoraId);
        entityManager.clear();
        mockMvc.perform(put("/api/companies/" + compradoraId + "/erp-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("erp", "SAP_S4HANA", "config",
                                Map.of("baseUrl", "https://sap.teste.local", "username", "kixima", "password", "segredo")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erp").value("SAP_S4HANA"));
        return compradoraId;
    }

    private String criarPo(String compradorToken, boolean esperaErpManaged) throws Exception {
        String supplierCompanyId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = 'AO-FOR-0001'", String.class);
        String productId = jdbcTemplate.queryForObject("SELECT id FROM products WHERE category = 'Hidráulica' AND supplier_id = ?", String.class, supplierCompanyId);
        var created = mockMvc.perform(post("/api/purchase-orders")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("supplierCompanyId", supplierCompanyId,
                                "items", List.of(Map.of("productId", productId, "quantity", 1))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.erpManaged").value(esperaErpManaged))
                .andExpect(jsonPath("$.status").value("AGUARDANDO_APROVACAO"))
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void umaPoSoNasceErpManagedComProEErpRealConfigurado() throws Exception {
        String adminToken = login(ADMIN_SISTEMA_EMAIL);
        String compradorToken = login(COMPRADOR_EMAIL);
        String companyAdminToken = login(COMPANY_ADMIN_EMAIL);
        String compradoraId = configurarErpReal(adminToken);

        String poId = criarPo(compradorToken, true);
        mockMvc.perform(get("/api/purchase-orders/" + poId).header("Authorization", "Bearer " + companyAdminToken))
                .andExpect(jsonPath("$.erpApprovalRequestedAt").isString());
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT direction::text FROM erp_sync_logs WHERE purchase_order_id = ? AND event_type = 'approval_requested'", String.class, poId))
                .isEqualTo("OUTBOUND");

        // Aprovar/rejeitar manualmente fica bloqueado numa PO erpManaged.
        mockMvc.perform(patch("/api/purchase-orders/" + poId + "/approve").header("Authorization", "Bearer " + companyAdminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("ERP configurado")));
        mockMvc.perform(patch("/api/purchase-orders/" + poId + "/reject")
                        .header("Authorization", "Bearer " + companyAdminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("reason", "Preço acima do orçamento"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("ERP configurado")));
        mockMvc.perform(get("/api/purchase-orders/" + poId).header("Authorization", "Bearer " + companyAdminToken))
                .andExpect(jsonPath("$.status").value("AGUARDANDO_APROVACAO"));

        // PRO sem ERP (MANUAL) -> a PO nasce normal.
        mockMvc.perform(put("/api/companies/" + compradoraId + "/erp-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("erp", "MANUAL", "config", Map.of()))))
                .andExpect(status().isOk());
        criarPo(compradorToken, false);

        // CORE (sem a feature erpIntegration), mesmo que houvesse ERP -> a PO nasce normal.
        jdbcTemplate.update("UPDATE companies SET plan = 'CORE', search_rank = 1 WHERE id = ?", compradoraId);
        entityManager.clear();
        criarPo(compradorToken, false);
    }

    @Test
    void callbackAssinadoAplicaADecisaoComAuditoriaENotificacaoEEIdempotente() throws Exception {
        String adminToken = login(ADMIN_SISTEMA_EMAIL);
        String compradorToken = login(COMPRADOR_EMAIL);
        String companyAdminToken = login(COMPANY_ADMIN_EMAIL);
        configurarErpReal(adminToken);
        String poId = criarPo(compradorToken, true);

        // HMAC inválido é recusado (401), a PO não muda.
        callback(evento("purchase_order.approval_decided", dados("poId", poId, "aprovado", true)), "assinatura-errada")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_SIGNATURE"));
        mockMvc.perform(get("/api/purchase-orders/" + poId).header("Authorization", "Bearer " + companyAdminToken))
                .andExpect(jsonPath("$.status").value("AGUARDANDO_APROVACAO"));

        callback(evento("purchase_order.approval_decided", dados("poId", poId, "aprovado", true, "erpExternalId", "SAP-DOA-00123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true))
                .andExpect(jsonPath("$.error").doesNotExist());
        mockMvc.perform(get("/api/purchase-orders/" + poId).header("Authorization", "Bearer " + companyAdminToken))
                .andExpect(jsonPath("$.status").value("APROVADA"))
                .andExpect(jsonPath("$.erpExternalId").value("SAP-DOA-00123"));
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT actor_name FROM audit_logs WHERE entity_type = 'PurchaseOrder' AND entity_id = ? AND action = 'PO_APROVADA_ERP'",
                String.class, poId)).isEqualTo("ERP");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE related_entity_type = 'PurchaseOrder' AND related_entity_id = ? AND type::text IN ('PO_APROVADA','PO_REJEITADA')",
                Integer.class, poId)).isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject("SELECT direction::text || '/' || status::text FROM erp_sync_logs WHERE purchase_order_id = ? AND event_type = 'approval_decided'",
                String.class, poId)).isEqualTo("INBOUND/SUCCESS");

        // Repetir o MESMO callback (reenvio do ERP) não reaplica nem duplica auditoria.
        callback(evento("purchase_order.approval_decided", dados("poId", poId, "aprovado", true, "erpExternalId", "SAP-DOA-00123")))
                .andExpect(status().isOk());
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_type = 'PurchaseOrder' AND entity_id = ? AND action = 'PO_APROVADA_ERP'",
                Integer.class, poId)).isEqualTo(1);

        // Decisão de rejeição aplica-se com o motivo.
        String outraPo = criarPo(compradorToken, true);
        callback(evento("purchase_order.approval_decided", dados("poId", outraPo, "aprovado", false, "motivo", "Excede o orçamento aprovado no ERP")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/purchase-orders/" + outraPo).header("Authorization", "Bearer " + companyAdminToken))
                .andExpect(jsonPath("$.status").value("REJEITADA"))
                .andExpect(jsonPath("$.rejectionReason").value(org.hamcrest.Matchers.containsString("orçamento aprovado no ERP")));
    }

    @Test
    void confirmacaoDePagamentoAvancaAPoParaPagaComReciboETaxaDaPlataforma() throws Exception {
        String adminToken = login(ADMIN_SISTEMA_EMAIL);
        String compradorToken = login(COMPRADOR_EMAIL);
        String companyAdminToken = login(COMPANY_ADMIN_EMAIL);
        String fornecedorToken = login(FORNECEDOR_EMAIL);
        configurarErpReal(adminToken);
        String poId = criarPo(compradorToken, true);

        // Antes da fatura, a confirmação é um erro de NEGÓCIO: recebida (200) mas registada como falha.
        callback(evento("payment.confirmed", dados("poId", poId, "erpExternalId", "SAP-PAY-CEDO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("ainda não tem fatura")));
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM erp_sync_logs WHERE purchase_order_id = ? AND direction = 'INBOUND' AND status = 'FAILED'", Integer.class, poId)).isEqualTo(1);

        callback(evento("purchase_order.approval_decided", dados("poId", poId, "aprovado", true))).andExpect(status().isOk());
        mockMvc.perform(patch("/api/purchase-orders/" + poId + "/accept").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AGUARDANDO_PAGAMENTO"));
        entityManager.flush();
        String invoiceId = jdbcTemplate.queryForObject("SELECT id FROM invoices WHERE purchase_order_id = ?", String.class, poId);
        var comFatura = mockMvc.perform(get("/api/purchase-orders/" + poId).header("Authorization", "Bearer " + companyAdminToken)).andReturn();
        String totalAmount = objectMapper.readTree(comFatura.getResponse().getContentAsString()).get("totalAmount").asText();

        callback(evento("payment.confirmed", dados("poId", poId, "erpExternalId", "SAP-PAY-00456", "valorPago", Double.parseDouble(totalAmount))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").doesNotExist());
        mockMvc.perform(get("/api/purchase-orders/" + poId).header("Authorization", "Bearer " + companyAdminToken))
                .andExpect(jsonPath("$.status").value("PAGA"))
                .andExpect(jsonPath("$.erpExternalId").value("SAP-PAY-00456"));
        entityManager.flush();
        entityManager.clear();
        assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM invoices WHERE id = ?", String.class, invoiceId)).isEqualTo("PAGA");
        assertThat(jdbcTemplate.queryForObject("SELECT canal::text FROM payments WHERE invoice_id = ?", String.class, invoiceId)).isEqualTo("ERP");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM platform_fees WHERE invoice_id = ?", Integer.class, invoiceId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'PAGAMENTO_CONFIRMADO_ERP' AND actor_name = 'ERP'", Integer.class)).isEqualTo(1);

        // Idempotente: reenviar a confirmação de pagamento não cria um segundo Payment.
        callback(evento("payment.confirmed", dados("poId", poId, "erpExternalId", "SAP-PAY-00456")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").doesNotExist());
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payments WHERE invoice_id = ?", Integer.class, invoiceId)).isEqualTo(1);

        // Tipo desconhecido: só se regista, confirma-se a receção.
        callback(evento("erp.sync.completed", dados("ok", true))).andExpect(status().isOk()).andExpect(jsonPath("$.received").value(true));
    }
}
