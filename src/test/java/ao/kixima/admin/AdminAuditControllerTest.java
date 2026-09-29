package ao.kixima.admin;

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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de paridade de contrato para o troço de adminRoutes.js portado
 * (GET /audit-logs) — exercitado contra um registo de auditoria REAL,
 * criado pelo próprio fluxo de PO (PoController.create grava PO_CRIADA),
 * não um fixture inserido à mão.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminAuditControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String ADMIN_SISTEMA_EMAIL = "admin@kixima.co.ao";
    private static final String SUPPLIER_TAX_ID = "AO-FOR-0001";
    private static final String PRODUCT_NAME = "Mangueira hidráulica de alta pressão 2\"";

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
    void listaTrilhoDeAuditoriaComEntradaRealDeUmaPoRecemCriada() throws Exception {
        String supplierCompanyId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, SUPPLIER_TAX_ID);
        String productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE supplier_id = ? AND name = ?", String.class, supplierCompanyId, PRODUCT_NAME);

        String compradorToken = login(COMPRADOR_EMAIL);
        var createRes = mockMvc.perform(post("/api/purchase-orders")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "supplierCompanyId", supplierCompanyId,
                                "items", List.of(Map.of("productId", productId, "quantity", 1))))))
                .andExpect(status().isCreated())
                .andReturn();
        String poId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText();
        entityManager.flush();
        entityManager.clear();

        String adminToken = login(ADMIN_SISTEMA_EMAIL);

        // Sem filtro: a entrada PO_CRIADA desta PO tem de aparecer.
        var res = mockMvc.perform(get("/api/admin/audit-logs").header("Authorization", "Bearer " + adminToken)
                        .param("action", "PO_CRIADA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.actions").isArray())
                .andReturn();
        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
        boolean encontrada = false;
        for (JsonNode item : body.get("items")) {
            if (poId.equals(item.get("entityId").asText(null))) {
                encontrada = true;
                assertTrue(item.get("detail").isObject(), "detail devia vir como objecto JSON, não uma string escapada.");
            }
        }
        assertTrue(encontrada, "A entrada PO_CRIADA desta PO devia aparecer no trilho filtrado por action=PO_CRIADA.");
    }

    @Test
    void auditLogsExigeAdminSistema() throws Exception {
        String compradorToken = login(COMPRADOR_EMAIL);
        mockMvc.perform(get("/api/admin/audit-logs").header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isForbidden());
    }
}
