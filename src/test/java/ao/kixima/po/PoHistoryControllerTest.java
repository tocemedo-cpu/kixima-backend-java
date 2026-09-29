package ao.kixima.po;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Espelha o bloco "Linha do tempo auditável (GET /:id/history)" de tests/po-recusa-e-timeline.test.js. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PoHistoryControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPANY_ADMIN_EMAIL = "admin@petroangola.co.ao";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String FORNECEDOR_EMAIL = "fornecedor@kianda.co.ao";
    private static final String ADMIN_SISTEMA_EMAIL = "admin@kixima.co.ao";
    private static final String OUTSIDER_EMAIL = "admin.alheia.hist@alheia-hist.co.ao";

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

    private String novaPoAprovada(String compradorToken, String companyAdminToken) throws Exception {
        String supplierCompanyId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = 'AO-FOR-0001'", String.class);
        String productId = jdbcTemplate.queryForObject("SELECT id FROM products WHERE category = 'Hidráulica' AND supplier_id = ?", String.class, supplierCompanyId);
        var created = mockMvc.perform(post("/api/purchase-orders")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("supplierCompanyId", supplierCompanyId,
                                "items", List.of(Map.of("productId", productId, "quantity", 1))))))
                .andExpect(status().isCreated())
                .andReturn();
        String poId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(patch("/api/purchase-orders/" + poId + "/approve").header("Authorization", "Bearer " + companyAdminToken))
                .andExpect(status().isOk());
        return poId;
    }

    private List<String> ids(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.get("id").asText()));
        return out;
    }

    @Test
    void registaPorOrdemCronologicaCadaTransicaoEEVisivelAsDuasPartesEAoAdmin() throws Exception {
        String compradorToken = login(COMPRADOR_EMAIL);
        String companyAdminToken = login(COMPANY_ADMIN_EMAIL);
        String fornecedorToken = login(FORNECEDOR_EMAIL);
        String adminToken = login(ADMIN_SISTEMA_EMAIL);
        String poId = novaPoAprovada(compradorToken, companyAdminToken);
        mockMvc.perform(patch("/api/purchase-orders/" + poId + "/accept").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk());

        var res = mockMvc.perform(get("/api/purchase-orders/" + poId + "/history").header("Authorization", "Bearer " + companyAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("PO_CRIADA"))
                .andExpect(jsonPath("$[1].action").value("PO_APROVADA"))
                .andExpect(jsonPath("$[2].action").value("PO_ACEITE"))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].actorName").isString())
                .andReturn();
        JsonNode timeline = objectMapper.readTree(res.getResponse().getContentAsString());
        List<Long> tempos = new ArrayList<>();
        timeline.forEach(n -> tempos.add(java.time.Instant.parse(n.get("createdAt").asText()).toEpochMilli()));
        assertThat(tempos).isSorted();

        // O comprador e o fornecedor da PO veem a mesma linha do tempo.
        var asBuyer = mockMvc.perform(get("/api/purchase-orders/" + poId + "/history").header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isOk()).andReturn();
        var asSupplier = mockMvc.perform(get("/api/purchase-orders/" + poId + "/history").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk()).andReturn();
        assertThat(ids(objectMapper.readTree(asBuyer.getResponse().getContentAsString())))
                .isEqualTo(ids(objectMapper.readTree(asSupplier.getResponse().getContentAsString())));

        // O Admin do Sistema vê a linha do tempo de qualquer PO.
        mockMvc.perform(get("/api/purchase-orders/" + poId + "/history").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void umaEmpresaAlheiaAPoLeva404() throws Exception {
        String compradorToken = login(COMPRADOR_EMAIL);
        String companyAdminToken = login(COMPANY_ADMIN_EMAIL);
        String poId = novaPoAprovada(compradorToken, companyAdminToken);

        String outsiderCompanyId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO companies (id, name, tax_id, type, status, contact_email, updated_at) "
                        + "VALUES (?, 'Empresa Alheia à Timeline Lda', ?, 'CLIENTE', 'APROVADA', 'geral@alheia-hist.co.ao', now())",
                outsiderCompanyId, "TAX-HIST-" + System.currentTimeMillis());
        String passwordHash = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, COMPANY_ADMIN_EMAIL);
        jdbcTemplate.update("INSERT INTO users (id, name, email, password_hash, role, company_id, active, updated_at) "
                        + "VALUES (?, 'Admin Alheia', ?, ?, 'COMPANY_ADMIN', ?, true, now())",
                UUID.randomUUID().toString(), OUTSIDER_EMAIL, passwordHash, outsiderCompanyId);
        entityManager.clear();
        String outsiderToken = login(OUTSIDER_EMAIL);

        mockMvc.perform(get("/api/purchase-orders/" + poId + "/history").header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound());
    }
}
