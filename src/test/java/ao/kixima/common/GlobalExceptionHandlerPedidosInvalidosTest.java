package ao.kixima.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Um pedido INVÁLIDO é um erro do cliente (4xx), nunca um 500.
 *
 * O que isto protege: o Spring MVC lança estas excepções antes de o método do controller correr, e sem
 * handler próprio elas caíam no fallback do GlobalExceptionHandler como `500 INTERNAL_ERROR` — um erro de
 * quem chama a parecer uma avaria do servidor. Foi o que se viu com o frontend ligado ao Java: um
 * Content-Type que não é JSON e um parâmetro de query em falta davam 500. No Node cada um destes casos é um
 * 4xx com o mesmo envelope `{error:{code,message}}`.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GlobalExceptionHandlerPedidosInvalidosTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String login(String email) throws Exception {
        var res = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", "Kixima@123"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void contentTypeQueNaoEJsonDa422EnaoDa500() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType("text/plain").content("x"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void pedidoSemContentTypeDa422EnaoDa500() throws Exception {
        mockMvc.perform(post("/api/auth/login").content("x"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    /** `page`/`limit` são `Integer`: um valor não numérico era um 500 (erro de conversão do Spring), agora é um 422 com o campo. */
    @Test
    void tipoErradoNumParametroDa422ComODetalheDoCampo() throws Exception {
        String token = login("comprador@petroangola.co.ao");
        mockMvc.perform(get("/api/notifications").param("page", "abc").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.fieldErrors.page[0]").value("Invalid"));
    }

    @Test
    void metodoNaoPermitidoDaOMesmo404DeRotaInexistenteDoNode() throws Exception {
        String token = login("comprador@petroangola.co.ao");
        mockMvc.perform(delete("/api/planos").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROUTE_NOT_FOUND"));
    }

    /**
     * O caso real que mostrou o problema: `GET /api/faturacao/agt-serie-payload` sem `ano`/`tipoDocumento`.
     * Antes: 500 para TODOS os papéis (inclusive os que não têm permissão). Agora segue a ordem do Node —
     * papel primeiro (403), depois configuração AGT (503) e só depois os parâmetros (422).
     */
    @Test
    void agtSeriePayloadRespeitaAOrdemDoNodeEnuncaDa500() throws Exception {
        String comprador = login("comprador@petroangola.co.ao");
        mockMvc.perform(get("/api/faturacao/agt-serie-payload").header("Authorization", "Bearer " + comprador))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        String admin = login("admin@kixima.co.ao");
        var res = mockMvc.perform(get("/api/faturacao/agt-serie-payload").header("Authorization", "Bearer " + admin))
                .andReturn().getResponse();
        // Sem AGT configurada neste ambiente de teste -> 503 (SERVICO_INDISPONIVEL); com ela -> 422. Nunca 500.
        JsonNode corpo = objectMapper.readTree(res.getContentAsString());
        org.junit.jupiter.api.Assertions.assertTrue(res.getStatus() == 503 || res.getStatus() == 422,
                "esperado 503 ou 422 mas foi " + res.getStatus() + ": " + corpo);
        assertEquals(res.getStatus() == 503 ? "SERVICO_INDISPONIVEL" : "VALIDATION_ERROR", corpo.at("/error/code").asText());
    }
}
