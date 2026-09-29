package ao.kixima.retention;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Espelha `GET /api/retencao` (app.js) — pública, texto legal alinhado com o que a limpeza aplica. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RetentionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void politicaDeRetencaoEPublicaEUsaAChaveOQueComUnderscore() throws Exception {
        var res = mockMvc.perform(get("/api/retencao"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.politica").isArray())
                .andExpect(jsonPath("$.politica[?(@.id=='notificacoes')].dias").value(180))
                .andExpect(jsonPath("$.politica[?(@.id=='financeiro')].dias").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())))
                .andReturn();
        JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(res.getResponse().getContentAsString());
        JsonNode notificacoes = null;
        for (JsonNode item : body.get("politica")) if ("notificacoes".equals(item.get("id").asText())) notificacoes = item;
        assertThat(notificacoes).isNotNull();
        assertThat(notificacoes.has("o_que")).isTrue();
        assertThat(notificacoes.get("prazo").asText()).isEqualTo("180 dias");
    }
}
