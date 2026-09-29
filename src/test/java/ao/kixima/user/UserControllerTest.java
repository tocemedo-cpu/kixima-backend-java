package ao.kixima.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Espelha tests/profile.test.js, tests/dados-pessoais.test.js e as rotas de idioma/avatar de userRoutes.js. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UserControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String FORNECEDOR_EMAIL = "fornecedor@kianda.co.ao";
    private static final String FINANCEIRO_EMAIL = "financeiro@petroangola.co.ao";
    private static final String ADMIN_SISTEMA_EMAIL = "admin@kixima.co.ao";
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d, 0x49, 0x48, 0x44, 0x52};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private String login(String email) throws Exception {
        var res = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void perfilPessoalComOMesmoFormatoParaTodasAsPersonas() throws Exception {
        mockMvc.perform(get("/api/users/profile").header("Authorization", "Bearer " + login(COMPRADOR_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("buyer"))
                .andExpect(jsonPath("$.cards.length()").value(4))
                .andExpect(jsonPath("$.cards[?(@.label == 'Valor Total Comprado')]").exists())
                .andExpect(jsonPath("$.company.name").isString())
                .andExpect(jsonPath("$.company.iban").doesNotExist())
                .andExpect(jsonPath("$.company.plan").doesNotExist())
                .andExpect(jsonPath("$.user.avatarUrl").hasJsonPath());
        mockMvc.perform(get("/api/users/profile").header("Authorization", "Bearer " + login(FORNECEDOR_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("supplier"))
                .andExpect(jsonPath("$.cards[?(@.label == 'Valor Total Vendido')]").exists());
        mockMvc.perform(get("/api/users/profile").header("Authorization", "Bearer " + login(FINANCEIRO_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.length()").value(4))
                .andExpect(jsonPath("$.company.name").isString());
        mockMvc.perform(get("/api/users/profile").header("Authorization", "Bearer " + login(ADMIN_SISTEMA_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("admin"))
                .andExpect(jsonPath("$.company").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.cards[?(@.label == 'Empresas')]").exists());

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + login(COMPRADOR_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(COMPRADOR_EMAIL))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void idiomaEAvatar() throws Exception {
        String token = login(COMPRADOR_EMAIL);
        mockMvc.perform(put("/api/users/me/locale").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"locale\":\"FR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("fr"));
        mockMvc.perform(put("/api/users/me/locale").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"locale\":\"de\"}"))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(multipart("/api/users/me/avatar").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("NO_FILE"));
        mockMvc.perform(multipart("/api/users/me/avatar")
                        .file(new MockMultipartFile("image", "foto.png", "image/png", PNG))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value(org.hamcrest.Matchers.startsWith("/api/uploads/")));
        mockMvc.perform(delete("/api/users/me/avatar").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").doesNotExist());
    }

    @Test
    void oTitularAcedeAosSeusDadosNumDocumentoParaDescarregar() throws Exception {
        mockMvc.perform(get("/api/users/me/dados-pessoais")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/me/dados-pessoais").header("Authorization", "Bearer " + login(COMPRADOR_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment; filename=\"kixima-dados-")))
                .andExpect(jsonPath("$.conta.email").value(COMPRADOR_EMAIL))
                .andExpect(jsonPath("$.conta.company.taxId").value("AO-CLI-0001"))
                .andExpect(jsonPath("$.atividade.ordensCriadas").isArray())
                .andExpect(jsonPath("$.atividade.registoDeAcoes").isArray())
                .andExpect(jsonPath("$.totais.ordensCriadas").isNumber());
    }

    @Test
    void eliminarEUmaAnonimizacaoQuePreservaOTrilhoEFechaAConta() throws Exception {
        String empresaId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = 'AO-CLI-0001'", String.class);
        String hash = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, COMPRADOR_EMAIL);
        String vitimaId = UUID.randomUUID().toString();
        String email = "esquecer-" + System.currentTimeMillis() + "@petroangola.co.ao";
        jdbcTemplate.update("INSERT INTO users (id, name, email, password_hash, role, company_id, active, locale, updated_at) "
                + "VALUES (?, 'Pessoa a Esquecer', ?, ?, 'COMPRADOR', ?, true, 'fr', now())", vitimaId, email, hash, empresaId);
        jdbcTemplate.update("INSERT INTO audit_logs (id, action, entity_type, actor_id, actor_name, created_at) "
                + "VALUES (?, 'PO_APROVADA', 'PurchaseOrder', ?, 'Pessoa a Esquecer', now())", UUID.randomUUID().toString(), vitimaId);
        jdbcTemplate.update("INSERT INTO notifications (id, user_id, type, channel, title, message, created_at) "
                + "VALUES (?, ?, 'PO_APROVADA', 'IN_APP', 'Aviso', 'Pessoal', now())", UUID.randomUUID().toString(), vitimaId);
        entityManager.clear();
        String token = login(email);

        // Exige a senha atual — é irreversível.
        mockMvc.perform(post("/api/users/me/anonimizar").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(objectMapper.writeValueAsString(Map.of("password", "senha-errada"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsStringIgnoringCase("senha atual")));
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM users WHERE id = ?", String.class, vitimaId)).isEqualTo("Pessoa a Esquecer");

        mockMvc.perform(post("/api/users/me/anonimizar").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(objectMapper.writeValueAsString(Map.of("password", PASSWORD, "motivo", "Pedido do titular"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registosDeAuditoriaPreservados").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.notificacoesEliminadas").value(1))
                .andExpect(jsonPath("$.motivo").value("Pedido do titular"))
                .andExpect(jsonPath("$.utilizador.active").value(false));
        entityManager.flush();
        entityManager.clear();

        Map<String, Object> depois = jdbcTemplate.queryForMap("SELECT name, email, locale, active FROM users WHERE id = ?", vitimaId);
        assertThat(depois.get("name")).isEqualTo("Utilizador anonimizado");
        assertThat((String) depois.get("email")).doesNotContain("esquecer-").endsWith("@anonimo.kixima");
        assertThat(depois.get("locale")).isNull();
        assertThat(depois.get("active")).isEqualTo(false);

        // O trilho SOBREVIVE, sem o nome — todas as linhas, incluindo o registo da própria anonimização.
        List<Map<String, Object>> trilho = jdbcTemplate.queryForList("SELECT action, actor_name FROM audit_logs WHERE actor_id = ? ORDER BY created_at", vitimaId);
        assertThat(trilho).hasSizeGreaterThanOrEqualTo(2); // o login também deixa rasto
        assertThat(trilho).allSatisfy(r -> assertThat(r.get("actor_name")).isEqualTo("Utilizador anonimizado"));
        assertThat(trilho.stream().map(r -> r.get("action"))).contains("PO_APROVADA", "DADOS_PESSOAIS_ANONIMIZADOS");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ?", Integer.class, vitimaId)).isZero();

        // A sessão deixa de valer imediatamente e não se volta a entrar com as credenciais antigas.
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().is4xxClientError());
        // A senha das contas de teste não muda com isto.
        login(COMPRADOR_EMAIL);
    }
}
