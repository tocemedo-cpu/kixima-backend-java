package ao.kixima.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Espelha tests/session-revocation.test.js — revogação de sessão server-side
 * (tokenVersion): logout global e troca de senha invalidam imediatamente os
 * JWT já emitidos. Usa um utilizador dedicado para não interferir com as
 * outras suites; {@code @Transactional} apaga-o no fim.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SessionRevocationTest {

    private static final String EMAIL = "sessao.teste@terceira.co.ao";
    private static final String PW = "Kixima@123";
    // COMPANY_ADMIN é perfil sensível — mínimo de 12 caracteres (ver
    // PasswordPolicy), também aplicado na troca de senha.
    private static final String NEW_PW = "NovaSenha@12";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void criarUtilizadorDedicado() {
        String companyId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO companies (id, name, tax_id, type, status, contact_email, updated_at) "
                        + "VALUES (?, 'Empresa Sessão Lda', ?, 'CLIENTE', 'APROVADA', 'geral@sessao.co.ao', now())",
                companyId, "TAX-SESS-" + System.currentTimeMillis());
        jdbcTemplate.update("INSERT INTO users (id, name, email, password_hash, role, company_id, active, updated_at) "
                        + "VALUES (?, 'Utilizador Sessão', ?, ?, 'COMPANY_ADMIN', ?, true, now())",
                UUID.randomUUID().toString(), EMAIL, passwordEncoder.encode(PW), companyId);
        entityManager.clear();
    }

    private int loginStatus(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andReturn().getResponse().getStatus();
    }

    private String login(String email) throws Exception {
        var res = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PW))))
                .andReturn();
        assertThat(res.getResponse().getStatus()).as("login " + email).isEqualTo(200);
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    private int me(String token) throws Exception {
        return mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus();
    }

    @Test
    void logoutInvalidaOTokenAtualUmNovoLoginVoltaAFuncionar() throws Exception {
        String token = login(EMAIL);
        // O token funciona.
        assertThat(me(token)).isEqualTo(200);
        // Logout global (revoga as sessões).
        assertThat(mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus()).isEqualTo(200);
        // O mesmo token deixou de ser aceite.
        assertThat(me(token)).isEqualTo(401);
        // Novo login → novo token válido.
        String token2 = login(EMAIL);
        assertThat(me(token2)).isEqualTo(200);
    }

    @Test
    void trocarASenhaInvalidaOsTokensAnteriores() throws Exception {
        String token = login(EMAIL);
        assertThat(me(token)).isEqualTo(200);
        var chg = mockMvc.perform(patch("/api/auth/password").header("Authorization", "Bearer " + token).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("currentPassword", PW, "newPassword", NEW_PW))))
                .andReturn();
        assertThat(chg.getResponse().getStatus()).as(chg.getResponse().getContentAsString()).isEqualTo(200);
        // O token emitido antes da troca deixou de ser válido.
        assertThat(me(token)).isEqualTo(401);
        // Login com a nova senha funciona.
        assertThat(loginStatus(EMAIL, NEW_PW)).isEqualTo(200);
    }
}
