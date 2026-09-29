package ao.kixima.creditnote;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de paridade de contrato para creditNoteService.js (tests/credit-note.test.js,
 * tests/agt-anular-fatura.test.js): a fatura original nunca é tocada, o
 * crédito nunca excede o saldo por creditar (mesmo em duas parcelas), só quem
 * emitiu a fatura (ou o Admin do Sistema) a corrige, e anular é uma NC pelo
 * valor total com a submissão à AGT visível — mesmo quando recusada.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CreditNoteControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String FORNECEDOR_EMAIL = "fornecedor@kianda.co.ao";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String COMPANY_ADMIN_EMAIL = "admin@petroangola.co.ao";
    private static final String ADMIN_SISTEMA_EMAIL = "admin@kixima.co.ao";

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

    private String invoiceId(String reference) {
        return jdbcTemplate.queryForObject("SELECT id FROM invoices WHERE reference = ?", String.class, reference);
    }

    private Map<String, Object> corpo(String motivo, Object amount) {
        Map<String, Object> m = new HashMap<>();
        if (motivo != null) m.put("motivo", motivo);
        if (amount != null) m.put("amount", amount);
        return m;
    }

    @Test
    void notaDeCreditoParcialNuncaExcedeOSaldoESoOFornecedorDaFaturaAEmite() throws Exception {
        String fornecedorToken = login(FORNECEDOR_EMAIL);
        String compradorToken = login(COMPRADOR_EMAIL);
        String companyAdminToken = login(COMPANY_ADMIN_EMAIL);
        String adminToken = login(ADMIN_SISTEMA_EMAIL);
        String id = invoiceId("FAT-2026-00002"); // 684.000 AOA
        String url = "/api/payments/invoices/" + id + "/notas-credito";

        // Exige motivo e um valor positivo.
        mockMvc.perform(post(url).header("Authorization", "Bearer " + fornecedorToken).contentType("application/json")
                        .content(objectMapper.writeValueAsString(corpo(null, 1000))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post(url).header("Authorization", "Bearer " + fornecedorToken).contentType("application/json")
                        .content(objectMapper.writeValueAsString(corpo("Devolução", 0))))
                .andExpect(status().isUnprocessableEntity());

        // O comprador não emite notas de crédito sobre faturas que recebe (nem o seu Company Admin).
        mockMvc.perform(post(url).header("Authorization", "Bearer " + companyAdminToken).contentType("application/json")
                        .content(objectMapper.writeValueAsString(corpo("Tentativa", 1000))))
                .andExpect(status().isForbidden());

        var res = mockMvc.perform(post(url).header("Authorization", "Bearer " + fornecedorToken).contentType("application/json")
                        .content(objectMapper.writeValueAsString(corpo("Devolução parcial de mercadoria", 342000))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value(startsWith("NC-")))
                .andExpect(jsonPath("$.amount").value(342000.0))
                .andExpect(jsonPath("$.netAmount").value(300000.0)) // 342.000 / 1,14
                .andExpect(jsonPath("$.taxAmount").value(42000.0))
                .andExpect(jsonPath("$.motivo").value("Devolução parcial de mercadoria"))
                .andExpect(jsonPath("$.currency").value("AOA"))
                .andReturn();
        String ncId = objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText();
        entityManager.flush();
        entityManager.clear();

        // A fatura original fica exatamente como estava — a correção é um documento à parte.
        assertThat(jdbcTemplate.queryForObject("SELECT amount FROM invoices WHERE id = ?", BigDecimal.class, id).compareTo(new BigDecimal("684000.00"))).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM invoices WHERE id = ?", String.class, id)).isEqualTo("PAGA");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'NOTA_CREDITO_EMITIDA' AND entity_id = ?", Integer.class, ncId)).isEqualTo(1);

        // Ainda cabe exatamente o resto — não é recusada.
        mockMvc.perform(post(url).header("Authorization", "Bearer " + fornecedorToken).contentType("application/json")
                        .content(objectMapper.writeValueAsString(corpo("Segunda parcela", 342000))))
                .andExpect(status().isCreated());
        entityManager.flush();
        entityManager.clear();

        // Mais um kwanza que seja excede o saldo da fatura.
        mockMvc.perform(post(url).header("Authorization", "Bearer " + fornecedorToken).contentType("application/json")
                        .content(objectMapper.writeValueAsString(corpo("A mais", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value(containsString("excede o saldo")));

        // Listagem: as partes (comprador, fornecedor) e o Admin do Sistema; mais ninguém.
        mockMvc.perform(get(url).header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get(url).header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].reference").value(startsWith("NC-")));
        mockMvc.perform(get(url).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));

        // Já totalmente creditada — não há saldo por anular.
        mockMvc.perform(post("/api/payments/invoices/" + id + "/anular").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value(containsString("totalmente creditada")));
    }

    @Test
    void anularCriaUmaNcPeloValorTotalComASubmissaoAgtVisivel() throws Exception {
        String fornecedorToken = login(FORNECEDOR_EMAIL);
        String companyAdminToken = login(COMPANY_ADMIN_EMAIL);
        String id = invoiceId("FAT-2026-00003"); // 5.643.000 AOA
        String url = "/api/payments/invoices/" + id + "/anular";

        // Só o fornecedor DESTA fatura (ou o Admin do Sistema) pode anulá-la.
        mockMvc.perform(post(url).header("Authorization", "Bearer " + companyAdminToken))
                .andExpect(status().isForbidden());

        // Sem motivo indicado, usa um motivo por omissão referindo a fatura. Sem chave AGT no perfil de
        // testes, a submissão da NC é recusada — a NC já criada continua válida e a recusa fica visível e gravada.
        var res = mockMvc.perform(post(url).header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.creditNote.reference").value(startsWith("NC-")))
                .andExpect(jsonPath("$.creditNote.amount").value(5643000.0))
                .andExpect(jsonPath("$.creditNote.motivo").value("Anulação da fatura FAT-2026-00003"))
                .andExpect(jsonPath("$.agtSubmission.sucesso").value(false))
                .andExpect(jsonPath("$.agtSubmission.erro.message").isString())
                .andExpect(jsonPath("$.creditNote.agtErro.message").isString())
                .andReturn();
        String ncId = objectMapper.readTree(res.getResponse().getContentAsString()).get("creditNote").get("id").asText();
        entityManager.flush();
        entityManager.clear();

        assertThat(jdbcTemplate.queryForObject("SELECT agt_erro IS NOT NULL FROM credit_notes WHERE id = ?", Boolean.class, ncId)).isTrue();
        // A fatura NÃO muda de estado: "anulada" calcula-se a partir do saldo creditado.
        assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM invoices WHERE id = ?", String.class, id)).isEqualTo("PAGA");

        mockMvc.perform(post(url).header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isConflict());
    }
}
