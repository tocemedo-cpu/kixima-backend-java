package ao.kixima.payment;

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de paridade de contrato para paymentService.js + paymentRoutes.js
 * (tests/payment-proof.test.js, tests/platform-fees.test.js): pagar SEM
 * comprovativo é recusado e a fatura continua pendente; COM comprovativo
 * grava ficheiro e metadados, fecha fatura e PO, gera a taxa da plataforma
 * para o fornecedor (à parte da fatura) e regista auditoria; só o
 * fornecedor confirma a receção, uma única vez.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PaymentControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String FINANCEIRO_EMAIL = "financeiro@petroangola.co.ao";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String FORNECEDOR_EMAIL = "fornecedor@kianda.co.ao";

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

    @Test
    void pagarComComprovativoFechaFaturaEPoGeraTaxaEOFornecedorConfirmaARececao() throws Exception {
        String financeiroToken = login(FINANCEIRO_EMAIL);
        String compradorToken = login(COMPRADOR_EMAIL);
        String fornecedorToken = login(FORNECEDOR_EMAIL);
        String invoiceId = jdbcTemplate.queryForObject("SELECT id FROM invoices WHERE reference = ?", String.class, "FAT-2026-00001");
        String kiandaId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, "AO-FOR-0001");

        // A fatura pendente aparece na fila do Financeiro, com a PO por baixo.
        mockMvc.perform(get("/api/payments/invoices/pending").header("Authorization", "Bearer " + financeiroToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.reference=='FAT-2026-00001')].purchaseOrder.reference").value("PO-2026-00002"));

        // O Comprador não paga — é o Financeiro que executa.
        mockMvc.perform(post("/api/payments/invoices/" + invoiceId + "/pay").header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isForbidden());

        // SEM comprovativo é rejeitado (422) e a fatura continua pendente.
        mockMvc.perform(post("/api/payments/invoices/" + invoiceId + "/pay").header("Authorization", "Bearer " + financeiroToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value(containsString("comprovativo")));
        assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM invoices WHERE id = ?", String.class, invoiceId)).isEqualTo("PENDENTE");

        // Acima de 10MB o multer aborta com LIMIT_FILE_SIZE, que o errorHandler.js
        // traduz em 413 com esta frase. O fileFilter (tipo) corre ANTES do limite,
        // por isso um ficheiro grande do tipo errado continua a ser 422.
        byte[] enorme = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy("%PDF-1.4\n".getBytes(StandardCharsets.UTF_8), 0, enorme, 0, 9);
        mockMvc.perform(multipart("/api/payments/invoices/" + invoiceId + "/pay")
                        .file(new MockMultipartFile("proof", "enorme.pdf", "application/pdf", enorme))
                        .header("Authorization", "Bearer " + financeiroToken))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.error.code").value("LIMIT_FILE_SIZE"))
                .andExpect(jsonPath("$.error.message").value("O ficheiro é demasiado grande. Reduza o tamanho da imagem e tente novamente."));
        mockMvc.perform(multipart("/api/payments/invoices/" + invoiceId + "/pay")
                        .file(new MockMultipartFile("proof", "enorme.txt", "text/plain", enorme))
                        .header("Authorization", "Bearer " + financeiroToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value("Documento inválido — use PDF ou imagem (PNG/JPG)."));
        assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM invoices WHERE id = ?", String.class, invoiceId)).isEqualTo("PENDENTE");

        MockMultipartFile proof = new MockMultipartFile("proof", "transferencia-bai.pdf", "application/pdf",
                "%PDF-1.4\n%comprovativo de teste\n".getBytes(StandardCharsets.UTF_8));
        var payRes = mockMvc.perform(multipart("/api/payments/invoices/" + invoiceId + "/pay").file(proof)
                        .header("Authorization", "Bearer " + financeiroToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value(startsWith("PAY-")))
                .andExpect(jsonPath("$.status").value("PROCESSADO"))
                .andExpect(jsonPath("$.proofUrl").isString())
                .andExpect(jsonPath("$.proofName").value("transferencia-bai.pdf"))
                .andExpect(jsonPath("$.receivedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.amount").value(2394000.0))
                // Sem chave AGT configurada no perfil de testes, o reenvio do FT falha — visível, nunca escondido, e o pagamento sucede na mesma.
                .andExpect(jsonPath("$.agtInvoiceResubmission.sucesso").value(false))
                .andExpect(jsonPath("$.agtInvoiceResubmission.erro.message").isString())
                .andReturn();
        JsonNode pagamento = objectMapper.readTree(payRes.getResponse().getContentAsString());
        String paymentId = pagamento.get("id").asText();
        entityManager.flush();
        entityManager.clear();

        assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM invoices WHERE id = ?", String.class, invoiceId)).isEqualTo("PAGA");
        assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM purchase_orders WHERE reference = 'PO-2026-00002'", String.class)).isEqualTo("PAGA");
        assertThat(jdbcTemplate.queryForObject("SELECT paid_at IS NOT NULL FROM purchase_orders WHERE reference = 'PO-2026-00002'", Boolean.class)).isTrue();

        // Taxa KIXIMA: 2.394.000 AOA / 900 ≈ 2.660 USD, abaixo do limiar → 1 × 8 + 15 = 23 USD, pendente, do fornecedor.
        Map<String, Object> fee = jdbcTemplate.queryForMap("SELECT company_id, status::text AS status, amount, currency, basis, po_count FROM platform_fees WHERE invoice_id = ?", invoiceId);
        assertThat(fee.get("company_id")).isEqualTo(kiandaId);
        assertThat(fee.get("status")).isEqualTo("PENDENTE");
        assertThat(((BigDecimal) fee.get("amount")).compareTo(new BigDecimal("23.00"))).isZero();
        assertThat(fee.get("currency")).isEqualTo("USD");
        assertThat(fee.get("basis")).isEqualTo("FIXO");

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'PAGAMENTO_EXECUTADO' AND entity_id = ?", Integer.class, paymentId)).isEqualTo(1);

        // Pagar de novo: a fatura já não está pendente.
        mockMvc.perform(multipart("/api/payments/invoices/" + invoiceId + "/pay").file(proof).header("Authorization", "Bearer " + financeiroToken))
                .andExpect(status().isConflict());

        // Histórico do comprador traz o pagamento com a fatura por baixo.
        mockMvc.perform(get("/api/payments/history").header("Authorization", "Bearer " + financeiroToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + paymentId + "')].invoice.reference").value("FAT-2026-00001"));

        // Quem não é o fornecedor da fatura NÃO confirma a receção.
        mockMvc.perform(patch("/api/payments/" + paymentId + "/confirm-received").header("Authorization", "Bearer " + financeiroToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/payments/" + paymentId + "/confirm-received").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedAt").isString())
                .andExpect(jsonPath("$.receivedById").isString());
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(patch("/api/payments/" + paymentId + "/confirm-received").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'RECECAO_VALOR_CONFIRMADA' AND entity_id = ?", Integer.class, paymentId)).isEqualTo(1);
    }
}
