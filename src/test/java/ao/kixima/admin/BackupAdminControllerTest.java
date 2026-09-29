package ao.kixima.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de paridade de contrato para o troço /backup + /backup/verificar de
 * adminRoutes.js: só ADMIN_SISTEMA (Operações); sem S3 o botão recusa com o
 * mesmo motivo que o agendamento (422); sem cópia registada, verificar diz
 * para fazer uma primeiro (400).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BackupAdminControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String ADMIN_SISTEMA_EMAIL = "admin@kixima.co.ao";
    private static final String COMPANY_ADMIN_EMAIL = "admin@petroangola.co.ao";

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

    @Test
    void copiaAgoraRecusaSemS3EVerificarPedeUmaCopiaPrimeiro() throws Exception {
        String adminToken = login(ADMIN_SISTEMA_EMAIL);
        String companyAdminToken = login(COMPANY_ADMIN_EMAIL);

        mockMvc.perform(post("/api/admin/backup").header("Authorization", "Bearer " + companyAdminToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/backup").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value(containsString("S3")));

        mockMvc.perform(post("/api/admin/backup/verificar").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.message").value(containsString("nenhuma cópia registada")));
    }
}
