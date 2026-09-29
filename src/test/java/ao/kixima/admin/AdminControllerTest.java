package ao.kixima.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ao.kixima.security.AdminArea.CADASTRO;
import static ao.kixima.security.AdminArea.FINANCEIRO;
import static ao.kixima.security.AdminArea.OPERACOES;
import static ao.kixima.security.AdminArea.SUPORTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Espelha tests/admin.test.js, tests/admin-areas.test.js (gestão de áreas),
 * tests/admin-invites.test.js e tests/prontidao.test.js (acesso, segredos,
 * forma) — a parte de adminRoutes.js portada em D.1.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String NEW_PW = "Bomba-Hidraulica-Assessor-7";
    private static final String ADMIN_SISTEMA_EMAIL = "admin@kixima.co.ao";
    private static final String COMPANY_ADMIN_EMAIL = "admin@petroangola.co.ao";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String S = "adminconvite";

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

    @Value("${kixima.auth.jwt-secret}")
    private String jwtSecret;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    private String login(String email) throws Exception {
        return login(email, PASSWORD);
    }

    private String login(String email, String password) throws Exception {
        var res = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    private String userId(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", String.class, email);
    }

    /** O seed não tem uma segunda conta ADMIN_SISTEMA; um assessor restrito a Suporte é criado como fixture. */
    private String criarAssessor(String email, String... areas) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO users (id, name, email, password_hash, role, admin_areas, active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'ADMIN_SISTEMA'::\"PersonaRole\", ?, true, now(), now())",
                id, "Assessor (teste)", email, passwordEncoder.encode(PASSWORD), areas);
        entityManager.clear();
        return id;
    }

    private String criarUtilizadorTemp() {
        String companyId = jdbcTemplate.queryForObject("SELECT company_id FROM users WHERE email = ?", String.class, COMPRADOR_EMAIL);
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO users (id, name, email, password_hash, role, company_id, active, created_at, updated_at) "
                        + "VALUES (?, 'Utilizador Temp', 'temp.perm@petroangola.co.ao', ?, 'COMPRADOR'::\"PersonaRole\", ?, true, now(), now())",
                id, passwordEncoder.encode("x"), companyId);
        entityManager.clear();
        return id;
    }

    private JsonNode criarConvite(String token, String name, String email, List<String> areas, int esperado) throws Exception {
        var res = mockMvc.perform(post("/api/admin/invites").header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", name, "email", email, "adminAreas", areas))))
                .andExpect(status().is(esperado)).andReturn();
        return json(res.getResponse().getContentAsString());
    }

    private String tokenOf(String inviteId) {
        entityManager.flush();
        return jdbcTemplate.queryForObject("SELECT token FROM employee_invites WHERE id = ?", String.class, inviteId);
    }

    private JsonNode aceitar(String token, String password, int esperado) throws Exception {
        var res = mockMvc.perform(post("/api/admin/invite/" + token + "/accept").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("password", password, "termsAccepted", true))))
                .andExpect(status().is(esperado)).andReturn();
        return json(res.getResponse().getContentAsString());
    }

    private int auditCount(String action, String entityRef) {
        entityManager.flush();
        return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = ? AND entity_ref = ?", Integer.class, action, entityRef);
    }

    // --- admin.test.js -------------------------------------------------------

    @Test
    void permissoesEAtividadesDoAdminDoSistema() throws Exception {
        String admin = login(ADMIN_SISTEMA_EMAIL);
        String comprador = login(COMPRADOR_EMAIL);
        String tempId = criarUtilizadorTemp();

        var lista = mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn();
        JsonNode users = json(lista.getResponse().getContentAsString());
        assertThat(users.size()).isGreaterThanOrEqualTo(5);
        assertThat(users.get(0).has("active")).isTrue();
        assertThat(users.get(0).has("companyName")).isTrue();

        mockMvc.perform(patch("/api/admin/users/" + tempId + "/status").header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content("{\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        assertThat(auditCount("UTILIZADOR_BLOQUEADO", "Utilizador Temp")).isEqualTo(1);
        mockMvc.perform(patch("/api/admin/users/" + tempId + "/status").header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content("{\"active\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));

        // Não pode bloquear a própria conta (400).
        mockMvc.perform(patch("/api/admin/users/" + userId(ADMIN_SISTEMA_EMAIL) + "/status").header("Authorization", "Bearer " + admin)
                        .contentType("application/json").content("{\"active\":false}"))
                .andExpect(status().isBadRequest());

        // Um não-admin não acede à administração (403).
        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + comprador)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/activities").header("Authorization", "Bearer " + comprador)).andExpect(status().isForbidden());

        // KPIs e feed de todo o sistema.
        var atividades = mockMvc.perform(get("/api/admin/activities").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.empresas").value(greaterThan(0)))
                .andReturn();
        JsonNode body = json(atividades.getResponse().getContentAsString());
        assertThat(body.get("items").size()).isGreaterThan(0);
        List<String> tipos = new ArrayList<>();
        body.get("items").forEach(i -> tipos.add(i.get("type").asText()));
        assertThat(tipos).contains("Ordem de Compra");
    }

    // --- admin-areas.test.js (gerir áreas) ------------------------------------

    @Test
    void gerirAreasEReservadoAoSuperAdmin() throws Exception {
        String superAdmin = login(ADMIN_SISTEMA_EMAIL);
        String superAdminId = userId(ADMIN_SISTEMA_EMAIL);
        String assessorId = criarAssessor("assessor.suporte.teste@kixima.co.ao", SUPORTE);
        String assessor = login("assessor.suporte.teste@kixima.co.ao");

        var r = mockMvc.perform(patch("/api/admin/users/" + assessorId + "/areas").header("Authorization", "Bearer " + superAdmin)
                        .contentType("application/json").content(objectMapper.writeValueAsString(Map.of("areas", List.of(SUPORTE, OPERACOES)))))
                .andExpect(status().isOk()).andReturn();
        JsonNode areas = json(r.getResponse().getContentAsString()).get("adminAreas");
        List<String> lidas = new ArrayList<>();
        areas.forEach(a -> lidas.add(a.asText()));
        assertThat(lidas).containsExactlyInAnyOrder(SUPORTE, OPERACOES);
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'AREAS_DE_ADMIN_ALTERADAS' AND entity_id = ?",
                Integer.class, assessorId)).isEqualTo(1);

        // Área desconhecida → 422.
        mockMvc.perform(patch("/api/admin/users/" + assessorId + "/areas").header("Authorization", "Bearer " + superAdmin)
                        .contentType("application/json").content("{\"areas\":[\"inventada\"]}"))
                .andExpect(status().isUnprocessableEntity());

        // O assessor NÃO pode atribuir áreas a ninguém — nem a si próprio.
        mockMvc.perform(patch("/api/admin/users/" + assessorId + "/areas").header("Authorization", "Bearer " + assessor)
                        .contentType("application/json").content("{\"areas\":[]}"))
                .andExpect(status().isForbidden());

        // Nem o Super Admin pode alterar as PRÓPRIAS áreas.
        mockMvc.perform(patch("/api/admin/users/" + superAdminId + "/areas").header("Authorization", "Bearer " + superAdmin)
                        .contentType("application/json").content("{\"areas\":[\"suporte\"]}"))
                .andExpect(status().isBadRequest());

        // "Gerir utilizadores" também é reservado ao Super Admin; o assessor de Suporte lê o trilho de auditoria.
        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + assessor)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/audit-logs").header("Authorization", "Bearer " + assessor)).andExpect(status().isOk());
    }

    // --- admin-invites.test.js ---------------------------------------------------

    @Test
    void criarConviteDeAssessorSoOSuperAdmin() throws Exception {
        String superAdmin = login(ADMIN_SISTEMA_EMAIL);
        String assessorEmail = "assessor." + S + "@kixima.co.ao";

        JsonNode criado = criarConvite(superAdmin, "Nova Assessora", assessorEmail, List.of(SUPORTE, CADASTRO), 201);
        assertThat(criado.get("status").asText()).isEqualTo("PENDENTE");
        List<String> areas = new ArrayList<>();
        criado.get("adminAreas").forEach(a -> areas.add(a.asText()));
        assertThat(areas).containsExactlyInAnyOrder(CADASTRO, SUPORTE);
        assertThat(criado.has("token")).isFalse();
        assertThat(auditCount("CONVITE_ADMIN_CRIADO", assessorEmail)).isEqualTo(1);

        criarConvite(superAdmin, "Sem Área", "semarea." + S + "@kixima.co.ao", List.of(), 422);
        criarConvite(superAdmin, "Área Errada", "areaerrada." + S + "@kixima.co.ao", List.of("inventada"), 422);
        criarConvite(superAdmin, "Duplicado", ADMIN_SISTEMA_EMAIL, List.of(SUPORTE), 409);

        // Um assessor restrito NÃO pode convidar outro administrador, mesmo tendo o papel ADMIN_SISTEMA.
        String autoelevaEmail = "autoeleva." + S + "@kixima.co.ao";
        JsonNode tentador = criarConvite(superAdmin, "Assessor Tentador", autoelevaEmail, List.of(SUPORTE), 201);
        aceitar(tokenOf(tentador.get("id").asText()), NEW_PW, 201);
        String assessor = login(autoelevaEmail, NEW_PW);
        criarConvite(assessor, "Outro", "outro." + S + "@kixima.co.ao", List.of(FINANCEIRO), 403);

        // Um Comprador não pode convidar administradores.
        criarConvite(login(COMPRADOR_EMAIL), "Intruso", "intruso." + S + "@kixima.co.ao", List.of(SUPORTE), 403);

        // A lista mostra os convites criados.
        var lista = mockMvc.perform(get("/api/admin/invites").header("Authorization", "Bearer " + superAdmin))
                .andExpect(status().isOk()).andReturn();
        List<String> emails = new ArrayList<>();
        json(lista.getResponse().getContentAsString()).forEach(i -> emails.add(i.get("email").asText()));
        assertThat(emails).contains(assessorEmail);
        mockMvc.perform(get("/api/admin/invites").header("Authorization", "Bearer " + assessor)).andExpect(status().isForbidden());
    }

    @Test
    void aceitacaoTokenValidoExpiradoReutilizado() throws Exception {
        String superAdmin = login(ADMIN_SISTEMA_EMAIL);
        String reusoEmail = "reuso." + S + "@kixima.co.ao";

        JsonNode criado = criarConvite(superAdmin, "Assessor Financeiro", reusoEmail, List.of(FINANCEIRO), 201);
        String token = tokenOf(criado.get("id").asText());

        // Resolução pública mostra as áreas — só para leitura.
        mockMvc.perform(get("/api/admin/invite/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminAreas[0]").value(FINANCEIRO))
                .andExpect(jsonPath("$.name").value("Assessor Financeiro"));

        JsonNode aceite = aceitar(token, NEW_PW, 201);
        assertThat(aceite.get("role").asText()).isEqualTo("ADMIN_SISTEMA");
        assertThat(aceite.get("active").asBoolean()).isTrue(); // sem "pendente de aprovação"
        assertThat(aceite.get("adminAreas").get(0).asText()).isEqualTo(FINANCEIRO);
        assertThat(aceite.has("passwordHash")).isFalse();

        // Login funciona imediatamente, e a PRÓPRIA RESPOSTA DO LOGIN já reflete as áreas do convite.
        var loginRes = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", reusoEmail, "password", NEW_PW))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.adminAreas[0]").value(FINANCEIRO))
                .andReturn();
        String assessorToken = json(loginRes.getResponse().getContentAsString()).get("token").asText();
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + assessorToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.adminAreas[0]").value(FINANCEIRO));
        assertThat(auditCount("CONVITE_ADMIN_ACEITO", reusoEmail)).isEqualTo(1);

        // O MESMO token não pode ser usado uma segunda vez.
        JsonNode segundo = aceitar(token, NEW_PW, 400);
        assertThat(segundo.get("error").get("message").asText()).containsIgnoringCase("já foi utilizado");

        // Token expirado é recusado.
        JsonNode expira = criarConvite(superAdmin, "Vai Expirar", "expirado." + S + "@kixima.co.ao", List.of(SUPORTE), 201);
        entityManager.flush();
        jdbcTemplate.update("UPDATE employee_invites SET expires_at = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(1)), expira.get("id").asText());
        entityManager.clear();
        JsonNode expirado = aceitar(tokenOf(expira.get("id").asText()), NEW_PW, 400);
        assertThat(expirado.get("error").get("message").asText()).containsIgnoringCase("expirou");

        // Token inexistente é recusado, não uma exceção crua.
        mockMvc.perform(get("/api/admin/invite/isto-nao-existe")).andExpect(status().isBadRequest());

        // Senha curta para ADMIN_SISTEMA (< 12) é recusada.
        JsonNode curta = criarConvite(superAdmin, "Senha Curta", "senhacurta." + S + "@kixima.co.ao", List.of(SUPORTE), 201);
        aceitar(tokenOf(curta.get("id").asText()), "Curta-1", 422);

        // Autoelevação: enviar adminAreas no corpo da aceitação é ignorado.
        String soSuporteEmail = "sosuporte." + S + "@kixima.co.ao";
        JsonNode soSuporte = criarConvite(superAdmin, "Só Suporte", soSuporteEmail, List.of(SUPORTE), 201);
        var accept = mockMvc.perform(post("/api/admin/invite/" + tokenOf(soSuporte.get("id").asText()) + "/accept").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("password", NEW_PW, "termsAccepted", true,
                                "adminAreas", List.of(FINANCEIRO, "faturacao", "apolices", "operacoes")))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode conta = json(accept.getResponse().getContentAsString());
        assertThat(conta.get("adminAreas").size()).isEqualTo(1);
        assertThat(conta.get("adminAreas").get(0).asText()).isEqualTo(SUPORTE);
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT array_to_string(admin_areas, ',') FROM users WHERE email = ?", String.class, soSuporteEmail))
                .isEqualTo(SUPORTE);
    }

    @Test
    void cancelarEReenviarSoOSuperAdmin() throws Exception {
        String superAdmin = login(ADMIN_SISTEMA_EMAIL);
        String canceladoEmail = "cancelado." + S + "@kixima.co.ao";
        JsonNode criado = criarConvite(superAdmin, "A Cancelar", canceladoEmail, List.of(SUPORTE), 201);
        String id = criado.get("id").asText();
        String tokenOriginal = tokenOf(id);

        mockMvc.perform(post("/api/admin/invites/" + id + "/cancel").header("Authorization", "Bearer " + superAdmin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELADO"));
        aceitar(tokenOriginal, NEW_PW, 400);
        assertThat(auditCount("CONVITE_ADMIN_CANCELADO", canceladoEmail)).isEqualTo(1);

        mockMvc.perform(post("/api/admin/invites/" + id + "/resend").header("Authorization", "Bearer " + superAdmin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDENTE"));
        String tokenNovo = tokenOf(id);
        assertThat(tokenNovo).isNotEqualTo(tokenOriginal);
        assertThat(auditCount("CONVITE_ADMIN_REENVIADO", canceladoEmail)).isEqualTo(1);

        // O token ANTIGO continua sem servir, mesmo depois do reenvio.
        aceitar(tokenOriginal, NEW_PW, 400);
        aceitar(tokenNovo, NEW_PW, 201);

        // Assessor restrito não pode reenviar nem cancelar convites de administrador.
        JsonNode alvo = criarConvite(superAdmin, "Alvo", "alvo." + S + "@kixima.co.ao", List.of(SUPORTE), 201);
        aceitar(tokenOf(alvo.get("id").asText()), NEW_PW, 201);
        String assessor = login("alvo." + S + "@kixima.co.ao", NEW_PW);
        mockMvc.perform(post("/api/admin/invites/" + alvo.get("id").asText() + "/resend").header("Authorization", "Bearer " + assessor))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/invites/" + alvo.get("id").asText() + "/cancel").header("Authorization", "Bearer " + assessor))
                .andExpect(status().isForbidden());
    }

    // --- prontidao.test.js ---------------------------------------------------------

    @Test
    void prontidaoAcessoSegredosEForma() throws Exception {
        String admin = login(ADMIN_SISTEMA_EMAIL);
        mockMvc.perform(get("/api/admin/prontidao").header("Authorization", "Bearer " + login(COMPRADOR_EMAIL))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/prontidao").header("Authorization", "Bearer " + login(COMPANY_ADMIN_EMAIL))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/prontidao")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/email-teste").header("Authorization", "Bearer " + login(COMPRADOR_EMAIL))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/email-teste")).andExpect(status().isUnauthorized());

        var res = mockMvc.perform(get("/api/admin/prontidao").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn();
        String texto = res.getResponse().getContentAsString();
        JsonNode body = json(texto);

        // NUNCA devolve o valor de um segredo.
        for (String s : List.of(jwtSecret, dbPassword)) {
            if (s != null && s.length() >= 8) assertThat(texto).doesNotContain(s);
        }
        assertThat(texto).doesNotContainPattern("postgres(ql)?://[^\"]*:[^\"@]+@");

        // Diz o host e a porta da base, que não são segredo.
        JsonNode db = null;
        for (JsonNode g : body.get("grupos")) if ("Base de dados".equals(g.get("grupo").asText())) db = g;
        assertThat(db).isNotNull();
        assertThat(db.get("checks").get(0).get("id").asText()).isEqualTo("db-url");
        assertThat(db.get("checks").get(1).get("id").asText()).isEqualTo("db-direct");
        assertThat(db.get("checks").get(0).get("detalhe").asText()).contains("localhost:5432");

        // Cada problema traz o que fazer; o resumo bate certo; o ambiente de teste não está pronto.
        int total = 0;
        int ok = 0;
        int avisos = 0;
        int falhas = 0;
        List<String> idsFalha = new ArrayList<>();
        for (JsonNode g : body.get("grupos")) {
            for (JsonNode c : g.get("checks")) {
                total++;
                switch (c.get("estado").asText()) {
                    case "ok" -> ok++;
                    case "aviso" -> avisos++;
                    default -> {
                        falhas++;
                        idsFalha.add(c.get("id").asText());
                    }
                }
                if (!"ok".equals(c.get("estado").asText())) assertThat(c.get("acao").asText().length()).isGreaterThan(20);
            }
        }
        assertThat(total).isGreaterThan(0);
        assertThat(body.get("resumo").get("total").asInt()).isEqualTo(total);
        assertThat(body.get("resumo").get("ok").asInt() + body.get("resumo").get("avisos").asInt() + body.get("resumo").get("falhas").asInt()).isEqualTo(total);
        assertThat(ok + avisos + falhas).isEqualTo(total);
        assertThat(idsFalha).contains("storage", "email", "backup-cron", "backup-bucket");

        // Email de teste em modo console: recusa-se a fingir (422), com a instrução do que definir.
        mockMvc.perform(post("/api/admin/email-teste").header("Authorization", "Bearer " + admin))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("EMAIL_PROVIDER")));
    }
}
