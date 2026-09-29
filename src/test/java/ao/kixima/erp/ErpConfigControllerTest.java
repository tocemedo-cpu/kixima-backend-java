package ao.kixima.erp;

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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de paridade de contrato para erpConfigService.js/companyController.js
 * (troço ERP): só ADMIN_SISTEMA/CADASTRO acede, a integração é um recurso do
 * plano PRO (guarda de {@link ao.kixima.plan.PlanService}), campos
 * obrigatórios por ERP, credenciais mascaradas na leitura e preservadas
 * quando o pedido reenvia o valor mascarado, e o teste de ligação sem o
 * microserviço de integração configurado (skip, nunca bloqueia).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ErpConfigControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String ADMIN_SISTEMA_EMAIL = "admin@kixima.co.ao";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String SUPPLIER_TAX_ID = "AO-FOR-0001";

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
    void configuracaoErpCompletaComGuardaDePlanoECredenciaisMascaradas() throws Exception {
        String supplierCompanyId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, SUPPLIER_TAX_ID);
        String adminToken = login(ADMIN_SISTEMA_EMAIL);
        String compradorToken = login(COMPRADOR_EMAIL);

        // Só ADMIN_SISTEMA acede.
        mockMvc.perform(get("/api/companies/" + supplierCompanyId + "/erp-config").header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isForbidden());

        // Estado inicial: MANUAL, sem configuração.
        mockMvc.perform(get("/api/companies/" + supplierCompanyId + "/erp-config").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erp").value("MANUAL"))
                .andExpect(jsonPath("$.systems").isArray())
                .andExpect(jsonPath("$.lastTest").doesNotExist());

        // A empresa está no plano CORE — a integração ERP é um recurso do plano PRO.
        mockMvc.perform(put("/api/companies/" + supplierCompanyId + "/erp-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("erp", "PRIMAVERA", "config", Map.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PLANO_INSUFICIENTE"));

        jdbcTemplate.update("UPDATE companies SET plan = 'PRO' WHERE id = ?", supplierCompanyId);
        entityManager.flush();
        entityManager.clear();

        // Campos obrigatórios em falta.
        mockMvc.perform(put("/api/companies/" + supplierCompanyId + "/erp-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("erp", "PRIMAVERA", "config", Map.of("baseUrl", "https://erp.kianda.co.ao")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"));

        // Configuração completa.
        var setRes = mockMvc.perform(put("/api/companies/" + supplierCompanyId + "/erp-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("erp", "PRIMAVERA", "config",
                                Map.of("baseUrl", "https://erp.kianda.co.ao", "apiKey", "segredo-123", "company", "KND1")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erp").value("PRIMAVERA"))
                .andExpect(jsonPath("$.config.baseUrl").value("https://erp.kianda.co.ao"))
                .andExpect(jsonPath("$.config.apiKey").value("••••••"))
                // Sem INTEGRATION_URL/TOKEN configurados neste ambiente de teste — nunca sincroniza.
                .andExpect(jsonPath("$.integrationSynced").value(false))
                .andReturn();
        entityManager.flush();
        entityManager.clear();

        // Reenviar com o segredo mascarado preserva a credencial anterior — nunca a apaga por engano.
        mockMvc.perform(put("/api/companies/" + supplierCompanyId + "/erp-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("erp", "PRIMAVERA", "config",
                                Map.of("baseUrl", "https://erp2.kianda.co.ao", "apiKey", "••••••", "company", "KND1")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config.baseUrl").value("https://erp2.kianda.co.ao"))
                .andExpect(jsonPath("$.config.apiKey").value("••••••"));
        entityManager.flush();
        entityManager.clear();

        // Teste de ligação — sem microserviço configurado, não bloqueia (skip), fica registado.
        mockMvc.perform(post("/api/companies/" + supplierCompanyId + "/erp-config/test").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").exists());
        entityManager.flush();
        entityManager.clear();

        // Trilho de auditoria próprio (SET × 2, TEST × 1).
        var auditRes = mockMvc.perform(get("/api/companies/" + supplierCompanyId + "/erp-config/audits").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode audits = objectMapper.readTree(auditRes.getResponse().getContentAsString());
        assertEquals(3, audits.size());
        assertEquals("TEST", audits.get(0).get("action").asText());

        // Voltar a Manual limpa a configuração.
        mockMvc.perform(put("/api/companies/" + supplierCompanyId + "/erp-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("erp", "MANUAL", "config", Map.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erp").value("MANUAL"));
        entityManager.flush();
        entityManager.clear();

        // Sem ERP real configurado, o teste de ligação recusa-se.
        mockMvc.perform(post("/api/companies/" + supplierCompanyId + "/erp-config/test").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"));
    }
}
