package ao.kixima.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Espelha tests/contract-billing.test.js (achados N1–N3 da auditoria):
 * N2 — POST /api/contracts exige que o COMPANY_ADMIN pertença à empresa cliente;
 * N3 — consolidate-billing exige que o utilizador seja parte do contrato (404);
 * N1 — uma segunda consolidação não reemite a mesma fatura para as mesmas call-offs.
 * E o passo seguinte do fluxo (paymentService.processPayment + platformFeeService.createForInvoice):
 * o Financeiro do CLIENTE do contrato paga a fatura consolidada (sem PO), as call-offs ficam
 * com paidAt, a taxa KIXIMA conta N POs e o fornecedor confirma a receção.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ContractControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPANY_ADMIN_EMAIL = "admin@petroangola.co.ao";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String FORNECEDOR_EMAIL = "fornecedor@kianda.co.ao";
    private static final String FINANCEIRO_EMAIL = "financeiro@petroangola.co.ao";
    private static final String ADMIN_SISTEMA_EMAIL = "admin@kixima.co.ao";
    private static final String OUTSIDER_EMAIL = "admin.outsider@terceira-contratos.co.ao";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private String login(String email) throws Exception {
        var res = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    /** O cliente do seed passa a PRO (o contrato-quadro é uma funcionalidade Pro do CLIENTE) e nasce uma terceira empresa alheia. */
    private String prepararEmpresas(String clientCompanyId) {
        jdbcTemplate.update("UPDATE companies SET plan = 'PRO', search_rank = 2 WHERE id = ?", clientCompanyId);
        String outsiderCompanyId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO companies (id, name, tax_id, type, status, contact_email, plan, search_rank, updated_at) "
                        + "VALUES (?, 'Terceira Empresa Contratos Lda', ?, 'CLIENTE', 'APROVADA', 'geral@terceira-contratos.co.ao', 'PRO', 2, now())",
                outsiderCompanyId, "TAX-CTR-" + System.currentTimeMillis());
        String passwordHash = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, COMPANY_ADMIN_EMAIL);
        jdbcTemplate.update("INSERT INTO users (id, name, email, password_hash, role, company_id, active, updated_at) "
                        + "VALUES (?, 'Admin Terceira', ?, ?, 'COMPANY_ADMIN', ?, true, now())",
                UUID.randomUUID().toString(), OUTSIDER_EMAIL, passwordHash, outsiderCompanyId);
        entityManager.clear();
        return outsiderCompanyId;
    }

    private Map<String, Object> corpoContrato(String clientCompanyId, String supplierCompanyId, String category, long totalValue, int prazo) {
        Map<String, Object> body = new HashMap<>();
        body.put("clientCompanyId", clientCompanyId);
        body.put("supplierCompanyId", supplierCompanyId);
        body.put("categoriesCovered", List.of(category));
        body.put("totalValue", totalValue);
        body.put("currency", "AOA");
        body.put("billingPeriodicity", "TRIMESTRAL");
        body.put("paymentTermDays", prazo);
        body.put("validFrom", Instant.now().minus(1, ChronoUnit.DAYS).toString());
        body.put("validUntil", Instant.now().plus(365, ChronoUnit.DAYS).toString());
        return body;
    }

    @Test
    void n2_soSePodeCriarContratoEmNomeDaPropriaEmpresaCliente() throws Exception {
        String clientCompanyId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = 'AO-CLI-0001'", String.class);
        String supplierCompanyId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = 'AO-FOR-0001'", String.class);
        prepararEmpresas(clientCompanyId);
        String outsiderToken = login(OUTSIDER_EMAIL);
        String companyAdminToken = login(COMPANY_ADMIN_EMAIL);

        mockMvc.perform(post("/api/contracts")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(corpoContrato(clientCompanyId, supplierCompanyId, "Válvulas", 1000, 30))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/contracts")
                        .header("Authorization", "Bearer " + companyAdminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(corpoContrato(clientCompanyId, supplierCompanyId, "Válvulas", 1000, 30))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientCompanyId").value(clientCompanyId))
                .andExpect(jsonPath("$.reference").value(org.hamcrest.Matchers.startsWith("CTR-")))
                .andExpect(jsonPath("$.status").value("ATIVO"))
                .andExpect(jsonPath("$.usedValue").value(0));
    }

    @Test
    void semPlanoProOContratoQuadroERecusado() throws Exception {
        String clientCompanyId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = 'AO-CLI-0001'", String.class);
        String supplierCompanyId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = 'AO-FOR-0001'", String.class);
        jdbcTemplate.update("UPDATE companies SET plan = 'BASE' WHERE id = ?", clientCompanyId);
        entityManager.clear();
        String companyAdminToken = login(COMPANY_ADMIN_EMAIL);
        mockMvc.perform(post("/api/contracts")
                        .header("Authorization", "Bearer " + companyAdminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(corpoContrato(clientCompanyId, supplierCompanyId, "Válvulas", 1000, 30))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PLANO_INSUFICIENTE"))
                .andExpect(jsonPath("$.error.details.planoNecessario").value("PRO"));
    }

    @Test
    void n1n3_callOffAutomaticaEConsolidacaoComPosseESemReemissao() throws Exception {
        String clientCompanyId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = 'AO-CLI-0001'", String.class);
        String supplierCompanyId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = 'AO-FOR-0001'", String.class);
        String productId = jdbcTemplate.queryForObject("SELECT id FROM products WHERE category = 'Válvulas' AND supplier_id = ?", String.class, supplierCompanyId);
        prepararEmpresas(clientCompanyId);
        String outsiderToken = login(OUTSIDER_EMAIL);
        String companyAdminToken = login(COMPANY_ADMIN_EMAIL);
        String compradorToken = login(COMPRADOR_EMAIL);
        String fornecedorToken = login(FORNECEDOR_EMAIL);
        String adminToken = login(ADMIN_SISTEMA_EMAIL);

        var contrato = mockMvc.perform(post("/api/contracts")
                        .header("Authorization", "Bearer " + companyAdminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(corpoContrato(clientCompanyId, supplierCompanyId, "Válvulas", 100_000_000L, 15))))
                .andExpect(status().isCreated())
                .andReturn();
        String contractId = objectMapper.readTree(contrato.getResponse().getContentAsString()).get("id").asText();

        // Checkout dentro da cobertura do contrato -> nasce Call-off automaticamente, já APROVADA.
        var created = mockMvc.perform(post("/api/purchase-orders")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("supplierCompanyId", supplierCompanyId,
                                "items", List.of(Map.of("productId", productId, "quantity", 1))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isCallOff").value(true))
                .andExpect(jsonPath("$.status").value("APROVADA"))
                .andExpect(jsonPath("$.contractId").value(contractId))
                .andReturn();
        String poId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        // O tecto do contrato consome-se com o LÍQUIDO (850 000, sem IVA).
        mockMvc.perform(get("/api/contracts/" + contractId).header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedValue").value(850000.0))
                .andExpect(jsonPath("$.callOffs.length()").value(1))
                .andExpect(jsonPath("$.callOffs[0].id").value(poId));
        mockMvc.perform(get("/api/contracts/" + contractId).header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/contracts").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + contractId + "')].clientCompany.name").value("Petro Angola Operações, Lda"));
        mockMvc.perform(get("/api/contracts").header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // Fornecedor aceita -> EM_EXECUCAO (sem fatura individual), elegível para consolidação.
        mockMvc.perform(patch("/api/purchase-orders/" + poId + "/accept").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EM_EXECUCAO"));
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM invoices WHERE purchase_order_id = ?", Integer.class, poId)).isZero();

        // N3: utilizador alheio ao contrato não pode forçar a sua consolidação (404).
        mockMvc.perform(post("/api/contracts/" + contractId + "/consolidate-billing").header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());

        // Parte do contrato consolida com sucesso — a call-off fica marcada.
        var fatura = mockMvc.perform(post("/api/contracts/" + contractId + "/consolidate-billing").header("Authorization", "Bearer " + companyAdminToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contractId").value(contractId))
                .andExpect(jsonPath("$.consolidatedPoIds[0]").value(poId))
                .andExpect(jsonPath("$.amount").value(969000.0)) // 850 000 + 14 % IVA
                .andExpect(jsonPath("$.referenciaPagamento").isString())
                .andReturn();
        String invoiceId = objectMapper.readTree(fatura.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(get("/api/purchase-orders/" + poId).header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consolidatedInvoiceId").value(invoiceId))
                .andExpect(jsonPath("$.paymentDueAt").isString());
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM invoice_lines WHERE invoice_id = ?", Integer.class, invoiceId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE type = 'FATURA_GERADA' AND related_entity_id = ?", Integer.class, invoiceId)).isEqualTo(1);

        // N1: uma segunda consolidação NÃO reemite a mesma call-off — não há pendentes (400).
        mockMvc.perform(post("/api/contracts/" + contractId + "/consolidate-billing").header("Authorization", "Bearer " + companyAdminToken))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM invoices WHERE contract_id = ?", Integer.class, contractId)).isEqualTo(1);

        // O Chat Comercial resolve o contexto "contract" contra o contrato.
        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("contextType", "contract", "contextId", contractId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supplierCompanyId").value(supplierCompanyId));
    }

    /** Checkout dentro da cobertura do contrato (nasce call-off) + aceitação pelo fornecedor (EM_EXECUCAO, sem fatura própria). */
    private String callOffAceite(String compradorToken, String fornecedorToken, String supplierCompanyId, String productId) throws Exception {
        var created = mockMvc.perform(post("/api/purchase-orders")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("supplierCompanyId", supplierCompanyId,
                                "items", List.of(Map.of("productId", productId, "quantity", 1))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isCallOff").value(true))
                .andReturn();
        String poId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(patch("/api/purchase-orders/" + poId + "/accept").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EM_EXECUCAO"));
        return poId;
    }

    @Test
    void pagamentoDaFaturaConsolidadaDeVariasCallOffs() throws Exception {
        String clientCompanyId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = 'AO-CLI-0001'", String.class);
        String supplierCompanyId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = 'AO-FOR-0001'", String.class);
        String productId = jdbcTemplate.queryForObject("SELECT id FROM products WHERE category = 'Válvulas' AND supplier_id = ?", String.class, supplierCompanyId);
        prepararEmpresas(clientCompanyId);
        String outsiderToken = login(OUTSIDER_EMAIL);
        String companyAdminToken = login(COMPANY_ADMIN_EMAIL);
        String compradorToken = login(COMPRADOR_EMAIL);
        String fornecedorToken = login(FORNECEDOR_EMAIL);
        String financeiroToken = login(FINANCEIRO_EMAIL);

        var contrato = mockMvc.perform(post("/api/contracts")
                        .header("Authorization", "Bearer " + companyAdminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(corpoContrato(clientCompanyId, supplierCompanyId, "Válvulas", 100_000_000L, 15))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode contratoJson = objectMapper.readTree(contrato.getResponse().getContentAsString());
        String contractId = contratoJson.get("id").asText();
        String contractReference = contratoJson.get("reference").asText();

        // Duas call-offs no período -> UMA fatura consolidada, sem PO: 2 × 969 000 (850 000 + 14 % IVA).
        String poA = callOffAceite(compradorToken, fornecedorToken, supplierCompanyId, productId);
        String poB = callOffAceite(compradorToken, fornecedorToken, supplierCompanyId, productId);
        var fatura = mockMvc.perform(post("/api/contracts/" + contractId + "/consolidate-billing").header("Authorization", "Bearer " + companyAdminToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.purchaseOrderId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.contractId").value(contractId))
                .andExpect(jsonPath("$.consolidatedPoIds.length()").value(2))
                .andExpect(jsonPath("$.amount").value(1938000.0))
                .andReturn();
        String invoiceId = objectMapper.readTree(fatura.getResponse().getContentAsString()).get("id").asText();

        // A fila do Financeiro (listPendingInvoices) traz a fatura consolidada — via contrato, com o contrato por baixo.
        mockMvc.perform(get("/api/payments/invoices/pending").header("Authorization", "Bearer " + financeiroToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + invoiceId + "')].contract.reference").value(contractReference))
                .andExpect(jsonPath("$[?(@.id=='" + invoiceId + "')].purchaseOrder").isEmpty());

        MockMultipartFile proof = new MockMultipartFile("proof", "transferencia-bai.pdf", "application/pdf",
                "%PDF-1.4\n%comprovativo de teste\n".getBytes(StandardCharsets.UTF_8));

        // A posse é do CLIENTE do contrato: uma empresa alheia não paga esta fatura (403), e ela continua pendente.
        mockMvc.perform(multipart("/api/payments/invoices/" + invoiceId + "/pay").file(proof).header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.message").value("Só pode pagar faturas da sua própria empresa."));
        assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM invoices WHERE id = ?", String.class, invoiceId)).isEqualTo("PENDENTE");

        var payRes = mockMvc.perform(multipart("/api/payments/invoices/" + invoiceId + "/pay").file(proof).header("Authorization", "Bearer " + financeiroToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invoiceId").value(invoiceId))
                .andExpect(jsonPath("$.reference").value(startsWith("PAY-")))
                .andExpect(jsonPath("$.status").value("PROCESSADO"))
                .andExpect(jsonPath("$.amount").value(1938000.0))
                .andExpect(jsonPath("$.currency").value("AOA"))
                .andExpect(jsonPath("$.proofName").value("transferencia-bai.pdf"))
                .andExpect(jsonPath("$.receivedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.agtInvoiceResubmission.sucesso").value(false))
                .andReturn();
        String paymentId = objectMapper.readTree(payRes.getResponse().getContentAsString()).get("id").asText();
        entityManager.flush();
        entityManager.clear();

        // Fatura PAGA; as call-offs ficam com paidAt mas NÃO mudam de estado (`updateMany({ data: { paidAt } })`).
        assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM invoices WHERE id = ?", String.class, invoiceId)).isEqualTo("PAGA");
        for (String poId : List.of(poA, poB)) {
            assertThat(jdbcTemplate.queryForObject("SELECT paid_at IS NOT NULL FROM purchase_orders WHERE id = ?", Boolean.class, poId)).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM purchase_orders WHERE id = ?", String.class, poId)).isEqualTo("EM_EXECUCAO");
        }

        // Taxa KIXIMA: 1 938 000 AOA / 900 ≈ 2 153,33 USD, ÷ 2 POs abaixo do limiar → 2 × 8 + 15 = 31 USD, do fornecedor.
        Map<String, Object> fee = jdbcTemplate.queryForMap("SELECT company_id, status::text AS status, amount, currency, basis, po_count FROM platform_fees WHERE invoice_id = ?", invoiceId);
        assertThat(fee.get("company_id")).isEqualTo(supplierCompanyId);
        assertThat(fee.get("status")).isEqualTo("PENDENTE");
        assertThat(fee.get("po_count")).isEqualTo(2);
        assertThat(((BigDecimal) fee.get("amount")).compareTo(new BigDecimal("31.00"))).isZero();
        assertThat(fee.get("currency")).isEqualTo("USD");
        assertThat(fee.get("basis")).isEqualTo("FIXO");

        // Auditoria dentro da transação; sem PO não há notificação "pagamento da PO X" ao fornecedor (como no Node).
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'PAGAMENTO_EXECUTADO' AND entity_id = ?", Integer.class, paymentId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE type = 'PAGAMENTO_PROCESSADO' AND related_entity_id = ?", Integer.class, paymentId)).isZero();

        // Pagar de novo: a fatura já não está pendente.
        mockMvc.perform(multipart("/api/payments/invoices/" + invoiceId + "/pay").file(proof).header("Authorization", "Bearer " + financeiroToken))
                .andExpect(status().isConflict());

        // Histórico do comprador (listPaymentHistory) traz o pagamento da fatura consolidada, com o contrato por baixo.
        mockMvc.perform(get("/api/payments/history").header("Authorization", "Bearer " + financeiroToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + paymentId + "')].invoice.contract.reference").value(contractReference));

        // A receção confirma-a o fornecedor do CONTRATO — uma única vez.
        mockMvc.perform(patch("/api/payments/" + paymentId + "/confirm-received").header("Authorization", "Bearer " + financeiroToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/payments/" + paymentId + "/confirm-received").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedAt").isString());
        entityManager.flush();
        entityManager.clear();
        mockMvc.perform(patch("/api/payments/" + paymentId + "/confirm-received").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isConflict());
    }
}
