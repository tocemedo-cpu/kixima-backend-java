package ao.kixima.marketplace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de paridade de contrato para o troço `/favorites` e
 * `/saved-searches` de marketplaceRoutes.js — favoriteService.js e as
 * queries directas a `prisma.savedSearch` (ver o troço final de
 * marketplaceRoutes.js).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MarketplaceControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String login(String email) throws Exception {
        var res = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    private String primeiroProdutoId(String token) throws Exception {
        var res = mockMvc.perform(get("/api/catalog").header("Authorization", "Bearer " + token)).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get(0).get("id").asText();
    }

    @Test
    void favoritosSaoIdempotentesENuncaDuplicam() throws Exception {
        String token = login(COMPRADOR_EMAIL);
        String productId = primeiroProdutoId(token);

        mockMvc.perform(get("/api/marketplace/favorites").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());

        // Adicionar duas vezes o mesmo produto — não duplica (upsert).
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/marketplace/favorites")
                            .header("Authorization", "Bearer " + token)
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(Map.of("productId", productId))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.productId").value(productId))
                    .andExpect(jsonPath("$.favorite").value(true));
        }

        var listaRes = mockMvc.perform(get("/api/marketplace/favorites").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode lista = objectMapper.readTree(listaRes.getResponse().getContentAsString());
        assertEquals(1, lista.size());
        assertEquals(productId, lista.get(0).asText());

        mockMvc.perform(delete("/api/marketplace/favorites/" + productId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorite").value(false));

        mockMvc.perform(get("/api/marketplace/favorites").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void produtoInexistenteNaoPodeSerFavoritado() throws Exception {
        String token = login(COMPRADOR_EMAIL);
        mockMvc.perform(post("/api/marketplace/favorites")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("productId", "id-que-nao-existe-de-todo"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void pesquisasGuardadasSaoCriadasListadasERemovidasSoPeloDono() throws Exception {
        String token = login(COMPRADOR_EMAIL);

        var criarRes = mockMvc.perform(post("/api/marketplace/saved-searches")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("label", "Válvulas de esfera", "query", "q=valvula&kind=PRODUTO"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("Válvulas de esfera"))
                .andExpect(jsonPath("$.query").value("q=valvula&kind=PRODUTO"))
                .andReturn();
        String id = objectMapper.readTree(criarRes.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/marketplace/saved-searches").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + id + "')]").exists());

        // Tentar remover como outro utilizador não faz nada — a query filtra por userId.
        String outroToken = login("fornecedor@kianda.co.ao");
        mockMvc.perform(delete("/api/marketplace/saved-searches/" + id).header("Authorization", "Bearer " + outroToken))
                .andExpect(status().isOk());
        var aindaLaRes = mockMvc.perform(get("/api/marketplace/saved-searches").header("Authorization", "Bearer " + token)).andReturn();
        JsonNode aindaLa = objectMapper.readTree(aindaLaRes.getResponse().getContentAsString());
        assertTrue(java.util.stream.StreamSupport.stream(aindaLa.spliterator(), false).anyMatch(s -> id.equals(s.get("id").asText())));

        mockMvc.perform(delete("/api/marketplace/saved-searches/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/marketplace/saved-searches").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + id + "')]").doesNotExist());
    }

    @Test
    void rotaDeFavoritosExigeSessao() throws Exception {
        mockMvc.perform(get("/api/marketplace/favorites")).andExpect(status().isUnauthorized());
    }
}
