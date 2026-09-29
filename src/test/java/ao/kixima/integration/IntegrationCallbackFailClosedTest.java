package ao.kixima.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Espelha tests/integration-callback.test.js: sem KIXIMA_CALLBACK_SECRET, o endpoint falha fechado (503). */
@SpringBootTest(properties = "kixima.integration.callback-secret=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntegrationCallbackFailClosedTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void semSegredoConfiguradoRecusaOCallback() throws Exception {
        mockMvc.perform(post("/api/integration/callback")
                        .contentType("application/json")
                        .content("{\"type\":\"erp.sync.completed\",\"data\":{\"ok\":true}}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("CALLBACK_NOT_CONFIGURED"));
    }
}
