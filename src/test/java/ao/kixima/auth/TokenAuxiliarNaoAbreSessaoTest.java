package ao.kixima.auth;

import ao.kixima.notification.EmailDispatchService;
import ao.kixima.security.JwtService;
import ao.kixima.testsupport.Contas;
import ao.kixima.testsupport.Contas.Persona;
import ao.kixima.user.User;
import ao.kixima.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Regressão da auditoria A1: um token AUXILIAR (desafio de 2FA, recuperação de senha, confirmação de email, convite) é
 * assinado com a mesma chave do token de sessão e tem {@code sub}/{@code tv}. Se o filtro de autenticação o aceitasse
 * como sessão, quem só soubesse a senha de uma conta com 2FA receberia o desafio no login e entrava SEM o código.
 * Cada token só serve para o seu passo; a sessão exige um token SEM o claim {@code t}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TokenAuxiliarNaoAbreSessaoTest {

    private static final String EMAIL = Contas.email(Persona.COMPRADOR);
    private static final String PASSWORD = Contas.senha();
    private static final Pattern CODIGO = Pattern.compile("\\b(\\d{6})\\b");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;

    @MockBean
    private EmailDispatchService emailDispatchService;

    private List<String> codigos;

    @BeforeEach
    void intercetarEnvio() {
        codigos = new ArrayList<>();
        doAnswer(inv -> {
            Matcher m = CODIGO.matcher(inv.<String>getArgument(2));
            if (m.find()) codigos.add(m.group(1));
            return new EmailDispatchService.EnvioDireto("teste", inv.getArgument(0), "teste@kixima.co.ao");
        }).when(emailDispatchService).enviarDireto(anyString(), anyString(), anyString());
    }

    private JsonNode entrar() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD)))).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private MvcResult pedir(String caminho, String token, Map<String, Object> corpo) throws Exception {
        var req = post(caminho).contentType("application/json");
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        if (corpo != null) req = req.content(objectMapper.writeValueAsString(corpo));
        return mockMvc.perform(req).andReturn();
    }

    /** Liga a 2FA por email na conta (o mesmo caminho que a pessoa faz na app). */
    private void ativarDoisPassos() throws Exception {
        String token = entrar().get("token").asText();
        assertThat(pedir("/api/auth/2fa/email/enviar", token, null).getResponse().getStatus()).isEqualTo(200);
        MvcResult ativar = pedir("/api/auth/2fa/enable", token, Map.of("code", codigos.get(codigos.size() - 1)));
        assertThat(ativar.getResponse().getStatus()).isEqualTo(200);
    }

    private int estadoDeMe(String token) throws Exception {
        return mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus();
    }

    @Test
    void odesafioDe2faNaoAbreSessaoComoBearerNemComoCookie() throws Exception {
        ativarDoisPassos();
        JsonNode login = entrar();
        // O login com a senha certa só devolve o DESAFIO — não há sessão até a pessoa dar o código.
        assertThat(login.get("requires2fa").asBoolean()).isTrue();
        assertThat(login.hasNonNull("token")).isFalse();
        String desafio = login.get("challenge").asText();

        assertThat(estadoDeMe(desafio)).as("desafio de 2FA como Bearer").isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/auth/me").cookie(new Cookie("kixima_sessao", desafio))).andReturn().getResponse().getStatus())
                .as("desafio de 2FA como cookie de sessão").isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + desafio)).andReturn().getResponse().getStatus())
                .as("noutra rota autenticada").isEqualTo(401);
    }

    @Test
    void odesafioContinuaAServirNoSeuPassoCertoEDaOrigemASessaoVerdadeira() throws Exception {
        ativarDoisPassos();
        JsonNode login = entrar();
        String desafio = login.get("challenge").asText();
        String codigo = codigos.get(codigos.size() - 1); // o código que o login acabou de enviar por email

        MvcResult verificado = pedir("/api/auth/2fa/verify", null, Map.of("challenge", desafio, "code", codigo));
        assertThat(verificado.getResponse().getStatus()).isEqualTo(200);
        String sessao = objectMapper.readTree(verificado.getResponse().getContentAsString()).get("token").asText();
        assertThat(estadoDeMe(sessao)).as("a sessão que sai do 2.º passo").isEqualTo(200);
        assertThat(estadoDeMe(desafio)).as("o desafio continua a não ser sessão").isEqualTo(401);
    }

    @Test
    void nenhumTokenAuxiliarAbreSessao() throws Exception {
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        Map<String, String> auxiliares = Map.of(
                "desafio 2FA", jwtService.sign2faChallenge(user.getId(), user.getTokenVersion()),
                "recuperação de senha", jwtService.signPasswordReset(user.getId(), user.getTokenVersion()),
                "confirmação de email", jwtService.signEmailVerification(user.getId()),
                "convite", jwtService.signInvite(user.getCompanyId(), "COMPRADOR", null));
        auxiliares.forEach((nome, token) -> {
            try {
                assertThat(estadoDeMe(token)).as(nome + " como Bearer").isEqualTo(401);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void oTokenDeSessaoNormalContinuaAFuncionar() throws Exception {
        String sessao = entrar().get("token").asText();
        assertThat(estadoDeMe(sessao)).isEqualTo(200);
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        String direto = jwtService.signAccessToken(user.getId(), user.getRole(), user.getCompanyId(), user.getTokenVersion());
        assertThat(estadoDeMe(direto)).isEqualTo(200);
    }
}
