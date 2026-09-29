package ao.kixima.porobo;

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

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de paridade de contrato para poRoboRoutes.js: só o Company Admin
 * configura regras, e nada se escreve sem o add-on ATIVO — nem indo direto
 * ao endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PoRoboControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPANY_ADMIN_EMAIL = "admin@petroangola.co.ao";
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
    void regrasSoDoCompanyAdminESoComOAddonAtivo() throws Exception {
        String adminToken = login(COMPANY_ADMIN_EMAIL);
        String fornecedorToken = login(FORNECEDOR_EMAIL);
        String petroangolaId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, "AO-CLI-0001");
        String valvulaId = jdbcTemplate.queryForObject("SELECT id FROM products WHERE name LIKE 'Válvula de esfera%' AND active", String.class);

        mockMvc.perform(get("/api/po-robot/regras").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/po-robot/regras").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        Map<String, Object> corpo = new HashMap<>();
        corpo.put("productId", valvulaId);
        corpo.put("mediaOrigem", "MANUAL");
        corpo.put("mediaMensal", 30);
        corpo.put("periodicidade", "SEMANAL");
        corpo.put("quantidade", 2);
        corpo.put("limiteMaximoUsd", 5000);

        // Sem add-on ativo, nada se escreve — nem indo direto ao endpoint.
        mockMvc.perform(post("/api/po-robot/regras").header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(objectMapper.writeValueAsString(corpo)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.message").value(containsString("add-on pago")));

        jdbcTemplate.update("INSERT INTO company_addons (id, company_id, addon_key, status, activated_at, valido_ate, created_at, updated_at) "
                        + "VALUES (?, ?, 'PO_ROBOT', 'ATIVO', now(), ?, now(), now())",
                UUID.randomUUID().toString(), petroangolaId, java.sql.Timestamp.from(Instant.now().plus(Duration.ofDays(30))));

        // Validação do corpo, tal como no Node.
        Map<String, Object> invalido = new HashMap<>(corpo);
        invalido.put("periodicidade", "DIARIA");
        mockMvc.perform(post("/api/po-robot/regras").header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(objectMapper.writeValueAsString(invalido)))
                .andExpect(status().isUnprocessableEntity());

        var criarRes = mockMvc.perform(post("/api/po-robot/regras").header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content(objectMapper.writeValueAsString(corpo)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ativo").value(true))
                .andExpect(jsonPath("$.quantidade").value(2))
                .andExpect(jsonPath("$.periodicidade").value("SEMANAL"))
                .andExpect(jsonPath("$.proximaExecucaoEm").exists())
                .andReturn();
        String id = objectMapper.readTree(criarRes.getResponse().getContentAsString()).get("id").asText();
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/po-robot/regras").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].product.name").value(containsString("Válvula de esfera")))
                .andExpect(jsonPath("$[0].product.currency").value("AOA"));

        mockMvc.perform(get("/api/po-robot/media-sugerida/" + valvulaId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.produtoId").value(valvulaId))
                .andExpect(jsonPath("$.mediaMensal").value(0));

        // `quantidade: null` explícito limpa a quantidade fixa; `ativo: false` desliga.
        mockMvc.perform(put("/api/po-robot/regras/" + id).header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json").content("{\"ativo\":false,\"quantidade\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(false))
                .andExpect(jsonPath("$.quantidade").doesNotExist())
                .andExpect(jsonPath("$.limiteMaximoUsd").value(5000));
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(delete("/api/po-robot/regras/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/po-robot/regras").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(delete("/api/po-robot/regras/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }
}
