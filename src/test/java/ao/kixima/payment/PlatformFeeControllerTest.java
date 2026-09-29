package ao.kixima.payment;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de paridade de contrato para platformFeeService.js (tests/platform-fees.test.js, B):
 * compute = (nº POs × PER_PO) + PER_INVOICE em USD abaixo do limiar, 0,20 % acima; o
 * pagamento gera a taxa ao fornecedor; o Admin vê o livro e marca como cobrada; o
 * fornecedor vê o seu extrato e não o de outra empresa.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PlatformFeeControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String FINANCEIRO_EMAIL = "financeiro@petroangola.co.ao";
    private static final String FORNECEDOR_EMAIL = "fornecedor@kianda.co.ao";
    private static final String ADMIN_SISTEMA_EMAIL = "admin@kixima.co.ao";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformFeeService platformFeeService;

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
    void computeEmUsdAbaixoEAcimaDoLimiar() {
        PlatformFeeService.Calculo abaixo = platformFeeService.compute(3, new BigDecimal("1000"));
        assertThat(abaixo.amount()).isEqualByComparingTo(platformFeeService.perPo().multiply(BigDecimal.valueOf(3)).add(platformFeeService.perInvoice()));
        assertThat(abaixo.currency()).isEqualTo("USD");
        assertThat(abaixo.basis()).isEqualTo("FIXO");

        // Acima do limiar: 0,20 % do valor, uma só vez — sem a parcela da fatura.
        PlatformFeeService.Calculo acima = platformFeeService.compute(1, new BigDecimal("20000"));
        assertThat(acima.basis()).isEqualTo("PERCENTUAL");
        assertThat(acima.perInvoice()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(acima.amount()).isEqualByComparingTo(new BigDecimal("40.00"));
    }

    @Test
    void oPagamentoGeraATaxaOAdminVeOLivroEMarcaCobradaEOFornecedorVeOSeuExtrato() throws Exception {
        String financeiroToken = login(FINANCEIRO_EMAIL);
        String fornecedorToken = login(FORNECEDOR_EMAIL);
        String adminToken = login(ADMIN_SISTEMA_EMAIL);
        String invoiceId = jdbcTemplate.queryForObject("SELECT id FROM invoices WHERE reference = ?", String.class, "FAT-2026-00001");
        String kiandaId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, "AO-FOR-0001");
        String petroangolaId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, "AO-CLI-0001");

        MockMultipartFile proof = new MockMultipartFile("proof", "trf.pdf", "application/pdf", "%PDF-1.4\n".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/payments/invoices/" + invoiceId + "/pay").file(proof).header("Authorization", "Bearer " + financeiroToken))
                .andExpect(status().isCreated());
        entityManager.flush();
        entityManager.clear();
        String feeId = jdbcTemplate.queryForObject("SELECT id FROM platform_fees WHERE invoice_id = ?", String.class, invoiceId);

        // Um não-admin não acede ao livro de taxas.
        mockMvc.perform(get("/api/admin/platform-fees").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/platform-fees").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.total").value(1))
                .andExpect(jsonPath("$.fees.length()").value(1))
                .andExpect(jsonPath("$.fees[0].company.name").value("Fornecedora Industrial Kianda, Lda"))
                .andExpect(jsonPath("$.fees[0].invoice.reference").value("FAT-2026-00001"))
                .andExpect(jsonPath("$.fees[0].status").value("PENDENTE"));

        mockMvc.perform(patch("/api/admin/platform-fees/" + feeId + "/charge").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COBRADO"))
                .andExpect(jsonPath("$.chargedAt").isString());
        entityManager.flush();
        entityManager.clear();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'TAXA_COBRADA' AND entity_id = ?", Integer.class, feeId)).isEqualTo(1);

        // O fornecedor vê o seu extrato ("quanto devo à KIXIMA e porquê") — com a fórmula e os totais.
        mockMvc.perform(get("/api/companies/" + kiandaId + "/platform-fees").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.company.taxId").value("AO-FOR-0001"))
                .andExpect(jsonPath("$.kpis.total").value(1))
                .andExpect(jsonPath("$.kpis.cobradas").value(1))
                .andExpect(jsonPath("$.kpis.chargedAOA").value(23.0))
                .andExpect(jsonPath("$.kpis.currency").value("USD"))
                .andExpect(jsonPath("$.formula.perPo").value(8))
                .andExpect(jsonPath("$.fees[0].invoice.reference").value("FAT-2026-00001"));

        // ... e não o de outra empresa.
        mockMvc.perform(get("/api/companies/" + petroangolaId + "/platform-fees").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/companies/" + kiandaId + "/platform-fees").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }
}
