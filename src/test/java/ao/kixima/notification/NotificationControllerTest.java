package ao.kixima.notification;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de paridade de contrato para notificationController.js/Routes.js,
 * exercitando o caminho real de ponta a ponta: criar uma PO (PoService)
 * dispara `notificationService.poAguardaAprovacao`, que grava uma
 * notificação para o Company Admin da compradora — não é um teste isolado
 * do NotificationService, é a prova de que a ligação (PoService ->
 * NotificationService) funciona, tal como o Node.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NotificationControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String COMPANY_ADMIN_EMAIL = "admin@petroangola.co.ao";
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
    void criarPoNotificaCompanyAdminDaCompradoraEListaParaEsseUtilizador() throws Exception {
        String supplierCompanyId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, SUPPLIER_TAX_ID);
        String productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE supplier_id = ? AND name = ?", String.class, supplierCompanyId, PRODUCT_NAME);

        String compradorToken = login(COMPRADOR_EMAIL);
        String companyAdminToken = login(COMPANY_ADMIN_EMAIL);

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

        // O Comprador que CRIOU a PO não é notificado (poAguardaAprovacao avisa o Company Admin, não o autor).
        var listaComprador = mockMvc.perform(get("/api/notifications").header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode corpoComprador = objectMapper.readTree(listaComprador.getResponse().getContentAsString());
        boolean compradorTemNotificacaoDestaPo = false;
        for (JsonNode n : corpoComprador.get("itens")) {
            if (poId.equals(n.get("relatedEntityId").asText(null))) compradorTemNotificacaoDestaPo = true;
        }
        assertFalse(compradorTemNotificacaoDestaPo);

        var listaAdmin = mockMvc.perform(get("/api/notifications").header("Authorization", "Bearer " + companyAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens").isArray())
                .andExpect(jsonPath("$.porLer").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andReturn();
        JsonNode corpoAdmin = objectMapper.readTree(listaAdmin.getResponse().getContentAsString());
        JsonNode notificacaoDaPo = null;
        for (JsonNode n : corpoAdmin.get("itens")) {
            if (poId.equals(n.get("relatedEntityId").asText(null))) notificacaoDaPo = n;
        }
        assertTrue(notificacaoDaPo != null, "Company Admin devia ter recebido a notificação PO_AGUARDA_APROVACAO desta PO.");
        assertEquals("PO_AGUARDA_APROVACAO", notificacaoDaPo.get("type").asText());
        assertEquals("PurchaseOrder", notificacaoDaPo.get("relatedEntityType").asText());
        assertTrue(notificacaoDaPo.get("readAt").isNull());

        // markRead.
        String notificationId = notificacaoDaPo.get("id").asText();
        mockMvc.perform(patch("/api/notifications/" + notificationId + "/read").header("Authorization", "Bearer " + companyAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readAt").exists());
    }

    @Test
    void listarNotificacoesSemSessaoDevolve401() throws Exception {
        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
    }
}
