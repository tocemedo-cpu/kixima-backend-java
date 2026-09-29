package ao.kixima.discount;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de paridade de contrato para discountThresholdService.js + o troço
 * "Lado KIXIMA" de categoryManagementRoutes.js: só ADMIN_SISTEMA/FINANCEIRO
 * gere os patamares de desconto, configuráveis em runtime.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DiscountThresholdControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String ADMIN_SISTEMA_EMAIL = "admin@kixima.co.ao";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String BASE = "/api/category-management/admin/thresholds";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    void gestaoDosPatamaresDeDescontoSoParaAdminDoSistema() throws Exception {
        String adminToken = login(ADMIN_SISTEMA_EMAIL);
        String compradorToken = login(COMPRADOR_EMAIL);

        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isForbidden());

        var listaRes = mockMvc.perform(get(BASE).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andReturn();
        JsonNode listaInicial = objectMapper.readTree(listaRes.getResponse().getContentAsString());
        assertEquals(3, listaInicial.size());
        // Ordenado por minVolumeUsd ascendente — o mais baixo primeiro.
        assertEquals(100000.0, listaInicial.get(0).get("minVolumeUsd").asDouble());

        // Percentagem fora do intervalo é rejeitada.
        mockMvc.perform(post(BASE)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("minVolumeUsd", 50000, "discountPercent", 150))))
                .andExpect(status().isUnprocessableEntity());

        var criarRes = mockMvc.perform(post(BASE)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("minVolumeUsd", 2000000, "discountPercent", 15))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ativo").value(true))
                .andReturn();
        String id = objectMapper.readTree(criarRes.getResponse().getContentAsString()).get("id").asText();
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(put(BASE + "/" + id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("ativo", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(false))
                // Campos não enviados mantêm-se.
                .andExpect(jsonPath("$.discountPercent").value(15.0));
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(delete(BASE + "/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get(BASE).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        mockMvc.perform(delete(BASE + "/id-que-nao-existe").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }
}
