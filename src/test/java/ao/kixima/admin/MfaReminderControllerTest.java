package ao.kixima.admin;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de paridade de contrato para o troço /mfa-pendentes + /mfa-lembrete
 * de adminRoutes.js: só ADMIN_SISTEMA (área Operações) vê a lista, e sem
 * email configurado (perfil de testes: EMAIL_PROVIDER=console) o envio é
 * recusado por extenso em vez de fingir que saiu.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MfaReminderControllerTest {

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
    void pendentesSoParaAdminDoSistemaELembreteRecusadoSemEmailConfigurado() throws Exception {
        String adminToken = login(ADMIN_SISTEMA_EMAIL);
        String companyAdminToken = login(COMPANY_ADMIN_EMAIL);

        mockMvc.perform(get("/api/admin/mfa-pendentes").header("Authorization", "Bearer " + companyAdminToken))
                .andExpect(status().isForbidden());

        var res = mockMvc.perform(get("/api/admin/mfa-pendentes").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andReturn();
        JsonNode lista = objectMapper.readTree(res.getResponse().getContentAsString());

        JsonNode admin = null;
        JsonNode companyAdmin = null;
        for (JsonNode item : lista) {
            if (ADMIN_SISTEMA_EMAIL.equals(item.get("email").asText())) admin = item;
            if (COMPANY_ADMIN_EMAIL.equals(item.get("email").asText())) companyAdmin = item;
        }
        // Ambos têm perfil obrigado a 2FA e ainda não a activaram — aparecem, com o último login preenchido
        // (acabaram de entrar — o LOGIN_SUCESSO fica no trilho) e sem lembrete ainda.
        assertThat(admin).isNotNull();
        assertThat(admin.get("perfil").asText()).isEqualTo("ADMIN_SISTEMA");
        assertThat(admin.get("empresa").isNull()).isTrue();
        assertThat(admin.get("ultimoLogin").isNull()).isFalse();
        assertThat(admin.get("ultimoLembrete").isNull()).isTrue();
        assertThat(companyAdmin).isNotNull();
        assertThat(companyAdmin.get("empresa").asText()).isEqualTo("Petro Angola Operações, Lda");

        mockMvc.perform(post("/api/admin/mfa-lembrete")
                        .header("Authorization", "Bearer " + companyAdminToken)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/mfa-lembrete")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"));
    }
}
