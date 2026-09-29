package ao.kixima.catalog;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de paridade de contrato para reviewService.js + o troço `/reviews`
 * de catalogController.js/catalogRoutes.js: só COMPRADOR avalia, um segundo
 * POST do mesmo comprador para o mesmo produto faz upsert (não duplica a
 * contagem), a média/contagem do produto são recalculadas a partir das
 * avaliações reais.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReviewControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String FORNECEDOR_EMAIL = "fornecedor@kianda.co.ao";

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
    void apenasCompradorAvaliaEUpsertNaoDuplicaContagem() throws Exception {
        String compradorToken = login(COMPRADOR_EMAIL);
        String fornecedorToken = login(FORNECEDOR_EMAIL);
        String productId = primeiroProdutoId(compradorToken);

        // Fornecedor não avalia — só COMPRADOR.
        mockMvc.perform(post("/api/catalog/" + productId + "/reviews")
                        .header("Authorization", "Bearer " + fornecedorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("rating", 5))))
                .andExpect(status().isForbidden());

        // Rating fora do intervalo 1..5 é rejeitado.
        mockMvc.perform(post("/api/catalog/" + productId + "/reviews")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("rating", 7))))
                .andExpect(status().isUnprocessableEntity());

        // Primeira avaliação.
        var r1 = mockMvc.perform(post("/api/catalog/" + productId + "/reviews")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("rating", 4, "comment", "Bom produto."))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode resumo1 = objectMapper.readTree(r1.getResponse().getContentAsString());
        assertEquals(4.0, resumo1.get("rating").asDouble(), 0.001);
        assertEquals(1, resumo1.get("reviewCount").asInt());

        // Segunda avaliação do MESMO comprador para o MESMO produto — upsert, não duplica.
        var r2 = mockMvc.perform(post("/api/catalog/" + productId + "/reviews")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("rating", 2, "comment", "Revi a opinião."))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode resumo2 = objectMapper.readTree(r2.getResponse().getContentAsString());
        assertEquals(2.0, resumo2.get("rating").asDouble(), 0.001);
        assertEquals(1, resumo2.get("reviewCount").asInt());

        // Listagem devolve a avaliação com o nome do autor.
        var listaRes = mockMvc.perform(get("/api/catalog/" + productId + "/reviews").header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode lista = objectMapper.readTree(listaRes.getResponse().getContentAsString());
        assertEquals(1, lista.size());
        assertEquals("Revi a opinião.", lista.get(0).get("comment").asText());
        assertEquals(true, lista.get(0).get("authorName").isTextual());

        // O produto reflecte a média/contagem recalculadas.
        mockMvc.perform(get("/api/catalog/" + productId).header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(2.0))
                .andExpect(jsonPath("$.reviewCount").value(1));
    }
}
