package ao.kixima.auth;

import ao.kixima.notification.EmailDispatchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Espelha backend/tests/password-reset.test.js — recuperação de senha
 * ("Esqueci a senha"): pedido anti-enumeração, redefinição com token de uso
 * único (via tokenVersion) e revogação das sessões antigas. Os emails são
 * capturados (sem os enviar de facto) tal como o `jest.spyOn(sendEmail)` do
 * Node — aqui simulando o EmailDispatchService, o caminho equivalente.
 * `@Transactional` repõe a senha original no fim — o Node fá-lo à mão no
 * afterAll.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PasswordResetControllerTest {

    private static final String EMAIL = "comprador@petroangola.co.ao";
    private static final String OLD_PASS = "Kixima@123";
    private static final String NEW_PASS = "NovaSenha#2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EmailDispatchService emailDispatchService;

    private record EmailEnviado(String to, String subject, String text, String html) {
    }

    private List<EmailEnviado> sentEmails;

    @BeforeEach
    void capturarEmails() {
        sentEmails = new ArrayList<>();
        doAnswer(inv -> {
            sentEmails.add(new EmailEnviado(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
            return null;
        }).when(emailDispatchService).dispatch(anyString(), anyString(), anyString(), any());
    }

    private int estado(String path, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(path).contentType("application/json").content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse().getStatus();
    }

    private String login(String email, String password) throws Exception {
        var res = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    /** Extrai o token do link /recuperar/<token> presente no corpo do email. */
    private String tokenFromEmail() {
        if (sentEmails.isEmpty()) return null;
        Matcher m = Pattern.compile("/recuperar/([\\w.-]+)").matcher(sentEmails.get(0).text());
        return m.find() ? m.group(1) : null;
    }

    @Test
    void pedidoDevolveSempreAMesmaRespostaExistaOEmailOuNao() throws Exception {
        var known = mockMvc.perform(post("/api/auth/forgot-password").contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of("email", EMAIL)))).andReturn().getResponse();
        var unknown = mockMvc.perform(post("/api/auth/forgot-password").contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of("email", "ninguem@nada.co.ao")))).andReturn().getResponse();
        assertThat(known.getStatus()).isEqualTo(200);
        assertThat(unknown.getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(known.getContentAsString())).isEqualTo(objectMapper.readTree(unknown.getContentAsString()));
        // Mas só o email existente recebe de facto a mensagem.
        assertThat(sentEmails).hasSize(1);
        assertThat(sentEmails.get(0).to()).isEqualTo(EMAIL);
        assertThat(sentEmails.get(0).text()).containsPattern("/recuperar/");
        assertThat(sentEmails.get(0).subject()).isEqualTo("Recuperação de senha — KIXIMA");
        assertThat(sentEmails.get(0).text()).startsWith("Olá ").contains("válido por 1 hora").endsWith("Equipe Kixima.");
        assertThat(sentEmails.get(0).html()).contains("Redefinir senha").contains("/recuperar/");
    }

    @Test
    void fluxoCompletoRedefinirSenhaAntigaMorreNovaEntraSessoesRevogadasTokenNaoReutilizavel() throws Exception {
        // Sessão ativa ANTES do reset (para verificar a revogação).
        String oldToken = login(EMAIL, OLD_PASS);

        estado("/api/auth/forgot-password", Map.of("email", EMAIL));
        String token = tokenFromEmail();
        assertThat(token).isNotBlank();

        assertThat(estado("/api/auth/reset-password", Map.of("token", token, "password", NEW_PASS))).isEqualTo(200);

        // Senha antiga deixa de funcionar; a nova entra.
        assertThat(estado("/api/auth/login", Map.of("email", EMAIL, "password", OLD_PASS))).isEqualTo(401);
        assertThat(estado("/api/auth/login", Map.of("email", EMAIL, "password", NEW_PASS))).isEqualTo(200);

        // As sessões antigas foram revogadas (tokenVersion incrementou).
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + oldToken)).andExpect(status().isUnauthorized());

        // O mesmo token de recuperação NÃO pode ser reutilizado.
        assertThat(estado("/api/auth/reset-password", Map.of("token", token, "password", "OutraSenha#99"))).isEqualTo(401);
    }

    @Test
    void tokenInvalidoERejeitado401() throws Exception {
        assertThat(estado("/api/auth/reset-password", Map.of("token", "abc.def.ghi", "password", NEW_PASS))).isEqualTo(401);
    }

    @Test
    void senhaCurtaERejeitada422() throws Exception {
        assertThat(estado("/api/auth/reset-password", Map.of("token", "abc.def.ghi", "password", "123"))).isEqualTo(422);
    }

    @Test
    void perfilSensivelCompanyAdmin11CaracteresNaoChega422() throws Exception {
        String adminEmail = "admin@petroangola.co.ao";
        estado("/api/auth/forgot-password", Map.of("email", adminEmail));
        String token = tokenFromEmail();
        assertThat(token).isNotBlank();

        // 11 caracteres: passa no schema (mínimo genérico 10) mas COMPANY_ADMIN
        // exige 12 — só a validação a nível de serviço (que conhece o role da
        // conta do token) apanha isto.
        assertThat(estado("/api/auth/reset-password", Map.of("token", token, "password", "Curta12345#"))).isEqualTo(422);

        // A senha do admin não mudou.
        assertThat(estado("/api/auth/login", Map.of("email", adminEmail, "password", "Kixima@123"))).isEqualTo(200);
    }
}
