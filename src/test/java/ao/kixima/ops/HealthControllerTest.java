package ao.kixima.ops;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Espelha tests/health.test.js — as sondas respondem sem sessão, e /ready confirma a base com a latência. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void sondasDeSaude() throws Exception {
        mockMvc.perform(get("/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok")).andExpect(jsonPath("$.env").value("test"));
        mockMvc.perform(get("/ready")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok")).andExpect(jsonPath("$.database").value("ok"))
                .andExpect(jsonPath("$.latencyMs").isNumber());
    }
}
