package ao.kixima.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Espelha backend/tests/email-idioma.test.js — os emails saem na língua do
 * destinatário, e a configuração em falta não passa em silêncio.
 *
 * Dois problemas reais: (1) o idioma escolhido vivia só no localStorage do
 * browser — que o servidor não vê, e é o servidor que escreve os emails;
 * (2) sem EMAIL_PROVIDER configurado, TUDO fica no log e o utilizador não vê
 * erro nenhum: simplesmente nunca recebe nada.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EmailI18nTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NotificationService notificationService;

    @PersistenceContext
    private EntityManager entityManager;

    @MockBean
    private EmailDispatchService emailDispatchService;

    private String login(String email) throws Exception {
        var res = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    // --- Idioma dos emails ------------------------------------------------------

    @Test
    void traduzParaALinguaDoDestinatario() {
        String chave = "Nova ordem de compra recebida";
        assertThat(EmailI18n.t(chave, "pt")).isEqualTo(chave);
        assertThat(EmailI18n.t(chave, "en")).isEqualTo("New purchase order received");
        assertThat(EmailI18n.t(chave, "fr")).isEqualTo("Nouveau bon de commande reçu");
    }

    @Test
    void semTraducaoDevolveOPortuguesNuncaFalha() {
        assertThat(EmailI18n.t("Frase que ninguém traduziu", "en")).isEqualTo("Frase que ninguém traduziu");
        assertThat(EmailI18n.t("Apólice atualizada", "de")).isEqualTo("Apólice atualizada");
        assertThat(EmailI18n.t("Apólice atualizada", null)).isEqualTo("Apólice atualizada");
    }

    @Test
    void substituiOsMarcadoresEmQualquerIdioma() {
        String chave = "Recebeu a ordem de compra {ref}. Reveja e aceite ou recuse.";
        assertThat(EmailI18n.t(chave, "en", Map.of("ref", "PO-2026-000042")))
                .isEqualTo("You have received purchase order PO-2026-000042. Review and accept or decline it.");
        assertThat(EmailI18n.t(chave, "pt", Map.of("ref", "PO-2026-000042"))).contains("PO-2026-000042");
    }

    @Test
    void normalizaOQueVierDaBase() {
        assertThat(EmailI18n.normalizar("EN")).isEqualTo("en");
        assertThat(EmailI18n.normalizar("fr-FR")).isEqualTo("fr");
        assertThat(EmailI18n.normalizar("klingon")).isEqualTo("pt");
        assertThat(EmailI18n.normalizar(null)).isEqualTo("pt");
        assertThat(EmailI18n.IDIOMAS).containsExactly("pt", "en", "fr");
    }

    /** O ponto de aplicação: notifyUser traduz o EMAIL, a notificação in-app fica em português. */
    @Test
    void oEmailDaNotificacaoSaiNaLinguaDoDestinatarioEAInAppFicaEmPortugues() {
        String userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", String.class, COMPRADOR_EMAIL);
        Notification n = notificationService.notifyUser(userId, NotificationType.PO_RECEBIDA_FORNECEDOR,
                "Nova ordem de compra recebida", "Recebeu a ordem de compra PO-1. Reveja e aceite ou recuse.",
                NotificationChannel.IN_APP_EMAIL, "PurchaseOrder", UUID.randomUUID().toString(), COMPRADOR_EMAIL, "fr");
        verify(emailDispatchService).dispatch(eq(COMPRADOR_EMAIL), eq("Nouveau bon de commande reçu"),
                eq("Recebeu a ordem de compra PO-1. Reveja e aceite ou recuse."));
        assertThat(n.getTitle()).isEqualTo("Nova ordem de compra recebida");
    }

    // --- Idioma guardado no servidor ------------------------------------------

    @Test
    void oUtilizadorGravaASuaEscolha() throws Exception {
        String token = login(COMPRADOR_EMAIL);
        mockMvc.perform(put("/api/users/me/locale").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"locale\":\"fr\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("fr"));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT locale FROM users WHERE email = ?", String.class, COMPRADOR_EMAIL))
                .isEqualTo("fr");
    }

    @Test
    void umIdiomaQueNaoExisteERecusado() throws Exception {
        String token = login(COMPRADOR_EMAIL);
        mockMvc.perform(put("/api/users/me/locale").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"locale\":\"klingon\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("pt, en, fr")));
    }

    @Test
    void semSessaoNaoSeAlteraOIdiomaDeNinguem() throws Exception {
        mockMvc.perform(put("/api/users/me/locale").contentType("application/json").content("{\"locale\":\"en\"}"))
                .andExpect(status().isUnauthorized());
    }

    // --- Configuração de email -------------------------------------------------

    private EmailDispatchService com(String provider, String brevoApiKey, String host, String user, String password) {
        return new EmailDispatchService(provider, "notificacoes@kixima.co.ao", brevoApiKey, host, "", user, password, new ObjectMapper());
    }

    @Test
    void oProviderConsoleEAssinaladoComoNenhumEnvioReal() {
        // É o estado por omissão — e o mais perigoso, porque parece funcionar.
        EmailDispatchService console = com("console", "", "", "", "");
        assertThat(console.apenasLog()).isTrue();
        assertThat(console.configurado()).isFalse();
        assertThat(com("brevo", "xkeysib-...", "", "", "").apenasLog()).isFalse();
    }

    @Test
    void umProviderAtivoSemCredenciaisEAssinalado() {
        assertThat(com("brevo", "", "", "", "").emFalta()).isEqualTo(List.of("BREVO_API_KEY"));
        assertThat(com("brevo", "xkeysib-...", "", "", "").emFalta()).isEmpty();
        assertThat(com("smtp", "", "smtp.brevo.com", "", "").emFalta()).isEqualTo(List.of("SMTP_USER", "SMTP_PASSWORD"));
        assertThat(com("smtp", "", "", "", "").emFalta()).isEqualTo(List.of("SMTP_HOST", "SMTP_USER", "SMTP_PASSWORD"));
        assertThat(com("smtp", "", "smtp.brevo.com", "u", "p").configurado()).isTrue();
        // Um provider desconhecido não exige nada — e enviarDireto não o recusa
        // pelo nome (segue pelo SMTP), tal como o Node.
        assertThat(com("ses", "", "", "", "").emFalta()).isEmpty();
    }
}
