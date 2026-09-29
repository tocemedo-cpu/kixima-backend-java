package ao.kixima.apicatalogo;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Espelha tests/api-catalogo.test.js — o alcance, a validação e o rasto da API de catálogo por chave (Lacunas D.8). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ApiCatalogoControllerTest {

    private static final String PASSWORD = "Kixima@123";
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

    private String fornecedoraId() {
        return jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = 'AO-FOR-0001'", String.class);
    }

    private void porNoPlano(String plano, int rank) {
        entityManager.flush();
        jdbcTemplate.update("UPDATE companies SET plan = ?::\"CompanyPlan\", search_rank = ? WHERE id = ?", plano, rank, fornecedoraId());
        entityManager.clear();
    }

    private JsonNode criarChave(String token, String nome) throws Exception {
        var res = mockMvc.perform(post("/api/catalog/api-keys").header("Authorization", "Bearer " + token).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("nome", nome))))
                .andExpect(status().isCreated()).andReturn();
        entityManager.flush();
        entityManager.clear();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private int comChave(String chave) throws Exception {
        return mockMvc.perform(get("/api/v1/catalogo").header("Authorization", "Bearer " + chave)).andReturn().getResponse().getStatus();
    }

    @Test
    void alcanceValidacaoERastoDaApiDeCatalogo() throws Exception {
        porNoPlano("PRO", 2);
        String fornecedor = login(FORNECEDOR_EMAIL);
        JsonNode criada = criarChave(fornecedor, "ERP de teste");
        String chave = criada.get("chave").asText();
        String chaveId = criada.get("id").asText();

        // Lê o catálogo da própria empresa — e SÓ o da própria empresa.
        var lista = mockMvc.perform(get("/api/v1/catalogo").header("Authorization", "Bearer " + chave))
                .andExpect(status().isOk())
                .andExpect(header().string("RateLimit-Limit", "30"))
                .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThan(0)))
                .andReturn();
        JsonNode body = objectMapper.readTree(lista.getResponse().getContentAsString());
        assertThat(body.get("itens").size()).isGreaterThan(0);
        for (JsonNode i : body.get("itens")) {
            if (i.get("sku").isNull()) continue;
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM products WHERE sku = ? AND supplier_id <> ?", Integer.class,
                    i.get("sku").asText(), fornecedoraId())).isEqualTo(0);
        }
        // Chave via x-api-key também entra; paginação saneada.
        mockMvc.perform(get("/api/v1/catalogo?limite=1&pagina=1").header("x-api-key", chave))
                .andExpect(status().isOk()).andExpect(jsonPath("$.limite").value(1)).andExpect(jsonPath("$.itens.length()").value(1));

        // Não chega a ordens, pagamentos nem utilizadores — a chave não é um JWT.
        for (String caminho : List.of("/api/purchase-orders", "/api/payments", "/api/users/profile", "/api/admin/prontidao")) {
            int status = mockMvc.perform(get(caminho).header("Authorization", "Bearer " + chave)).andReturn().getResponse().getStatus();
            assertThat(status).isBetween(401, 499);
        }
        // Sem chave, não se lê nada; uma chave inventada não entra, e a mensagem não diz porquê.
        mockMvc.perform(get("/api/v1/catalogo")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("SEM_CHAVE"));
        mockMvc.perform(get("/api/v1/catalogo").header("Authorization", "Bearer kxm_deadbeef.inventada"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message").value("Chave inválida, revogada ou sem acesso à API."));

        // Atualizar preço e stock — a razão de a API existir.
        List<String> skus = jdbcTemplate.queryForList("SELECT sku FROM products WHERE supplier_id = ? AND sku IS NOT NULL LIMIT 1", String.class, fornecedoraId());
        String sku;
        if (skus.isEmpty()) {
            sku = "TESTE-API-001";
            jdbcTemplate.update("UPDATE products SET sku = ? WHERE id = (SELECT id FROM products WHERE supplier_id = ? LIMIT 1)", sku, fornecedoraId());
            entityManager.clear();
        } else {
            sku = skus.get(0);
        }
        mockMvc.perform(patch("/api/v1/catalogo/" + sku).header("Authorization", "Bearer " + chave).contentType("application/json")
                        .content("{\"preco\": 12345.67, \"stock\": 42}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.preco").value(12345.67)).andExpect(jsonPath("$.stock").value(42));
        mockMvc.perform(get("/api/v1/catalogo/" + sku).header("Authorization", "Bearer " + chave))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sku").value(sku)).andExpect(jsonPath("$.stock").value(42));
        var invalido = mockMvc.perform(patch("/api/v1/catalogo/" + sku).header("Authorization", "Bearer " + chave).contentType("application/json")
                        .content("{\"preco\": -1, \"stock\": 2.5}"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        String mensagem = objectMapper.readTree(invalido.getResponse().getContentAsString()).get("error").get("message").asText();
        assertThat(mensagem).contains("preco").contains("stock");
        mockMvc.perform(patch("/api/v1/catalogo/" + sku).header("Authorization", "Bearer " + chave).contentType("application/json").content("{}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("NADA_A_ALTERAR"));
        mockMvc.perform(patch("/api/v1/catalogo/SKU-QUE-NAO-EXISTE").header("Authorization", "Bearer " + chave).contentType("application/json").content("{\"preco\": 1}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NAO_ENCONTRADO"));

        // A alteração fica no trilho, com a chave identificada.
        mockMvc.perform(patch("/api/v1/catalogo/" + sku).header("Authorization", "Bearer " + chave).contentType("application/json").content("{\"preco\": 999}"))
                .andExpect(status().isOk());
        entityManager.flush();
        Map<String, Object> registo = jdbcTemplate.queryForMap("SELECT actor_name, detail::text AS detail FROM audit_logs WHERE action = 'CATALOGO_ATUALIZADO_POR_API' ORDER BY created_at DESC LIMIT 1");
        assertThat((String) registo.get("actor_name")).startsWith("API (kxm_");
        assertThat((String) registo.get("detail")).contains("unitPrice");

        // Revogar corta o acesso de imediato; o registo sobrevive; descer de plano corta as chaves existentes; o uso fica carimbado.
        JsonNode paraRevogar = criarChave(fornecedor, "Para revogar");
        assertThat(comChave(paraRevogar.get("chave").asText())).isEqualTo(200);
        mockMvc.perform(delete("/api/catalog/api-keys/" + paraRevogar.get("id").asText()).header("Authorization", "Bearer " + fornecedor)).andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        assertThat(comChave(paraRevogar.get("chave").asText())).isEqualTo(401);
        assertThat(comChave(chave)).isEqualTo(200);
        porNoPlano("CORE", 1);
        assertThat(comChave(chave)).isEqualTo(401);
        porNoPlano("PRO", 2);
        assertThat(comChave(chave)).isEqualTo(200);
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT ultimo_uso FROM api_keys WHERE id = ?", java.sql.Timestamp.class, chaveId)).isNotNull();

        // Limite de taxa por chave (janela fixa de um minuto; 30 no perfil de teste, 120 em produção), depois 429 com RATE_LIMIT.
        JsonNode limitada = criarChave(fornecedor, "Limite");
        String chaveLimitada = limitada.get("chave").asText();
        int aceites = 0;
        int status = 200;
        for (int i = 0; i < 35 && status == 200; i++) {
            status = comChave(chaveLimitada);
            if (status == 200) aceites++;
        }
        assertThat(aceites).isEqualTo(30);
        mockMvc.perform(get("/api/v1/catalogo").header("Authorization", "Bearer " + chaveLimitada))
                .andExpect(status().is(429))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMIT"));
    }
}
