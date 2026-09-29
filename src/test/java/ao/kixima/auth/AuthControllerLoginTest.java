package ao.kixima.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de paridade de contrato (plano, secção 4): usa a MESMA conta de
 * teste que backend/tests/helpers.js semeia para o Node
 * (comprador@petroangola.co.ao / Kixima@123, na mesma base Postgres local
 * partilhada), confirmando login end-to-end pelo backend Java. `@Transactional`
 * na classe garante que nada fica persistido depois do teste — a conta
 * partilhada com o Node sai exactamente como entrou.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthControllerLoginTest {

    private static final String EMAIL = "comprador@petroangola.co.ao";
    private static final String PASSWORD = "Kixima@123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void loginComCredenciaisValidasDevolveTokenEPermiteMe() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.user.email").value(EMAIL))
                .andExpect(jsonPath("$.user.role").value("COMPRADOR"))
                .andReturn();

        String token = objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(EMAIL));
    }

    @Test
    void loginComSenhaErradaDevolve401ComOMesmoEnvelopeDoNode() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", EMAIL, "password", "senha-errada-qualquer"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.message").value("Credenciais inválidas."));
    }

    @Test
    void meSemTokenDevolve401RotaProtegida() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.message").value("Sessão em falta. Inicie sessão para continuar."));
    }

    @Test
    void loginComEmailInexistenteDevolveAMesmaMensagemDeCredenciaisInvalidas() throws Exception {
        // Anti-enumeração: a mensagem tem de ser idêntica à de senha errada (auth.js/authService.js).
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", "ninguem-" + System.nanoTime() + "@teste.ao", "password", "qualquercoisa123"))))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(res.getResponse().getContentAsString()).contains("Credenciais inválidas.");
    }
}
