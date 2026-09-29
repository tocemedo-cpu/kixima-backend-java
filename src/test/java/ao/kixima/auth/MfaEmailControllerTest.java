package ao.kixima.auth;

import ao.kixima.common.error.BadGatewayException;
import ao.kixima.notification.EmailDispatchService;
import ao.kixima.security.TotpService;
import ao.kixima.user.User;
import ao.kixima.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Espelha backend/tests/mfa-email.test.js — verificação em dois passos por
 * EMAIL. O que estes testes protegem, por ordem de importância:
 * <ol>
 *   <li>NINGUÉM pode ficar trancado fora da conta. Ativar a 2FA por email num
 *   servidor sem email configurado seria exatamente isso, e em silêncio;</li>
 *   <li>o código é uma credencial: uso único, validade curta, tentativas
 *   contadas, e guardado em hash;</li>
 *   <li>quem já usava a app continua a entrar pela app.</li>
 * </ol>
 * O envio é intercetado (simulando o EmailDispatchService, o equivalente ao
 * `jest.spyOn(enviarEmailDireto)`) e o código guardado — é o que o utilizador
 * receberia. Um servidor com email a funcionar é o pressuposto de todo este
 * método: a simulação responde "não é só log, não falta nada".
 * `@Transactional` repõe a conta no fim de cada teste (o afterEach do Node).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MfaEmailControllerTest {

    private static final String EMAIL = "comprador@petroangola.co.ao";
    private static final String PASSWORD = "Kixima@123";
    private static final Pattern CODIGO = Pattern.compile("\\b(\\d{6})\\b");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TotpService totpService;

    @Autowired
    private MfaEmailService mfaEmailService;

    @PersistenceContext
    private EntityManager entityManager;

    @MockBean
    private EmailDispatchService emailDispatchService;

    private record Enviado(String para, String assunto, String corpo, String codigo) {
    }

    private List<Enviado> enviados;

    @BeforeEach
    void intercetarEnvio() {
        enviados = new ArrayList<>();
        doAnswer(inv -> {
            String corpo = inv.getArgument(2);
            Matcher m = CODIGO.matcher(corpo);
            enviados.add(new Enviado(inv.getArgument(0), inv.getArgument(1), corpo, m.find() ? m.group(1) : null));
            return new EmailDispatchService.EnvioDireto("teste", inv.getArgument(0), "teste@kixima.co.ao");
        }).when(emailDispatchService).enviarDireto(anyString(), anyString(), anyString());
    }

    // --- Auxiliares ---------------------------------------------------------

    private JsonNode entrar() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD)))).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private MvcResult pedir(String path, String token, Map<String, Object> body) throws Exception {
        var req = post(path).contentType("application/json");
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        if (body != null) req = req.content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(req).andReturn();
    }

    private JsonNode json(MvcResult res) throws Exception {
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private String mensagemDeErro(MvcResult res) throws Exception {
        return json(res).get("error").get("message").asText();
    }

    private User conta() {
        return userRepository.findByEmail(EMAIL).orElseThrow();
    }

    private String hashNaBase() {
        entityManager.flush();
        return jdbcTemplate.queryForObject("SELECT mfa_code_hash FROM users WHERE email = ?", String.class, EMAIL);
    }

    // --- Ativar a 2FA por email ---------------------------------------------

    @Test
    void oCodigoChegaAoEmailDaPessoaEAtivaAVerificacao() throws Exception {
        String token = entrar().get("token").asText();

        MvcResult envio = pedir("/api/auth/2fa/email/enviar", token, null);
        assertThat(envio.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(envio).get("enviadoPara").asText()).matches(".*@petroangola\\.co\\.ao$");
        assertThat(json(envio).get("validadeMinutos").asInt()).isEqualTo(10);
        assertThat(json(envio).has("reaproveitado")).isFalse();
        assertThat(enviados).hasSize(1);
        assertThat(enviados.get(0).para()).isEqualTo(EMAIL);
        assertThat(enviados.get(0).assunto()).isEqualTo("Código para ativar a verificação em dois passos");

        MvcResult ativar = pedir("/api/auth/2fa/enable", token, Map.of("code", enviados.get(0).codigo()));
        assertThat(ativar.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(ativar).get("enabled").asBoolean()).isTrue();
        assertThat(json(ativar).get("metodo").asText()).isEqualTo("EMAIL");
    }

    /** O ponto mais importante do ficheiro. */
    @Test
    void numServidorSemEmailConfiguradoRecusaSeAAtivar() throws Exception {
        // Ativar aqui deixaria a pessoa sem forma nenhuma de voltar a entrar — e sem
        // erro nenhum a explicar porquê. Vale mais não ter 2FA do que trancar toda
        // a gente fora da plataforma.
        when(emailDispatchService.apenasLog()).thenReturn(true);

        String token = entrar().get("token").asText();
        MvcResult res = pedir("/api/auth/2fa/email/enviar", token, null);
        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        assertThat(mensagemDeErro(res)).containsPattern("(?i)inacessível|não está configurado");
        assertThat(enviados).isEmpty();
    }

    /**
     * Um "Ocorreu um erro interno" aqui é inútil para as duas pessoas envolvidas:
     * quem está a ativar não sabe o que fazer, e quem administra não sabe o que
     * corrigir. O erro do fornecedor de email é o que diz ambas as coisas.
     */
    @Test
    void seOEnvioFalharDizOMotivoEmVezDeErroInterno() throws Exception {
        doThrow(new BadGatewayException("Brevo API 401: Key not found"))
                .when(emailDispatchService).enviarDireto(anyString(), anyString(), anyString());
        String token = entrar().get("token").asText();
        MvcResult res = pedir("/api/auth/2fa/email/enviar", token, null);

        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        assertThat(mensagemDeErro(res)).contains("Key not found");
        assertThat(mensagemDeErro(res)).containsPattern("(?i)não ative a verificação por email");

        // E não fica um código pendente que ninguém recebeu.
        assertThat(hashNaBase()).isNull();
    }

    @Test
    void oEstadoAvisaAInterfaceAntesDeDeixarTentar() throws Exception {
        when(emailDispatchService.apenasLog()).thenReturn(true);
        String token = entrar().get("token").asText();
        MvcResult res = mockMvc.perform(get("/api/auth/2fa/status").header("Authorization", "Bearer " + token)).andReturn();
        assertThat(json(res).get("emailIndisponivel").asText()).containsPattern("(?i)não está configurado");
        assertThat(json(res).get("email").asText()).isEqualTo("c****r@petroangola.co.ao");
    }

    @Test
    void oEmailMostradoVemMascarado() {
        assertThat(mfaEmailService.mascarar("comprador@petroangola.co.ao")).isEqualTo("c****r@petroangola.co.ao");
        assertThat(mfaEmailService.mascarar("ab@x.ao")).isEqualTo("a@x.ao");
    }

    @Test
    void oCodigoNaoFicaEmTextoNaBaseDeDados() throws Exception {
        String token = entrar().get("token").asText();
        pedir("/api/auth/2fa/email/enviar", token, null);
        String hash = hashNaBase();
        assertThat(hash).doesNotContain(enviados.get(0).codigo());
        assertThat(new BCryptPasswordEncoder().matches(enviados.get(0).codigo(), hash)).isTrue();
        // Mesmo custo do Node (bcrypt.hash(codigo, 10)).
        assertThat(hash).startsWith("$2a$10$");
    }

    // --- O código é uma credencial, e trata-se como tal ---------------------

    private record ComCodigo(String token, String codigo) {
    }

    private ComCodigo comCodigo() throws Exception {
        String token = entrar().get("token").asText();
        pedir("/api/auth/2fa/email/enviar", token, null);
        return new ComCodigo(token, enviados.get(0).codigo());
    }

    @Test
    void eDeUsoUnicoNaoServeDuasVezes() throws Exception {
        ComCodigo c = comCodigo();
        assertThat(pedir("/api/auth/2fa/enable", c.token(), Map.of("code", c.codigo())).getResponse().getStatus()).isEqualTo(200);

        // Desativa e tenta reutilizar o mesmo código.
        User u = conta();
        u.setTotpEnabledAt(null);
        u.setMfaMethod(null);
        MvcResult outra = pedir("/api/auth/2fa/enable", c.token(), Map.of("code", c.codigo()));
        assertThat(outra.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
    }

    @Test
    void expiraNaoFicaValidoParaSempre() throws Exception {
        ComCodigo c = comCodigo();
        conta().setMfaCodeExpiraEm(Instant.now().minusSeconds(1));
        MvcResult res = pedir("/api/auth/2fa/enable", c.token(), Map.of("code", c.codigo()));
        assertThat(res.getResponse().getStatus()).isEqualTo(401);
        assertThat(mensagemDeErro(res)).containsPattern("(?i)expirou");
    }

    @Test
    void aoFimDePoucasTentativasOCodigoMorre() throws Exception {
        ComCodigo c = comCodigo();
        MvcResult ultima = null;
        for (int i = 0; i < MfaEmailService.TENTATIVAS_MAX; i++) {
            ultima = pedir("/api/auth/2fa/enable", c.token(), Map.of("code", "000000"));
        }
        assertThat(mensagemDeErro(ultima)).containsPattern("(?i)esgotadas");
        assertThat(hashNaBase()).isNull();
    }

    @Test
    void oReenvioETravadoNaoSeUsaAPlataformaParaInundarUmaCaixaDeCorreio() throws Exception {
        ComCodigo c = comCodigo();
        MvcResult res = pedir("/api/auth/2fa/email/enviar", c.token(), null);
        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        assertThat(mensagemDeErro(res)).containsPattern("Aguarde \\d+ segundos");
        assertThat(enviados).hasSize(1);
    }

    @Test
    void cadaCodigoEDiferenteDoAnterior() throws Exception {
        String token = entrar().get("token").asText();
        Set<String> vistos = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            conta().setMfaCodeEnviadoEm(null);
            pedir("/api/auth/2fa/email/enviar", token, null);
            vistos.add(enviados.get(enviados.size() - 1).codigo());
        }
        assertThat(vistos).hasSize(5);
    }

    // --- Entrar com a 2FA por email -----------------------------------------

    private void ativar() throws Exception {
        String token = entrar().get("token").asText();
        pedir("/api/auth/2fa/email/enviar", token, null);
        pedir("/api/auth/2fa/enable", token, Map.of("code", enviados.get(0).codigo()));
        enviados.clear();
    }

    @Test
    void aSenhaDeixaDeBastarEOCodigoChegaAoEmail() throws Exception {
        ativar();
        JsonNode login = entrar();
        assertThat(login.get("requires2fa").asBoolean()).isTrue();
        assertThat(login.get("metodo").asText()).isEqualTo("EMAIL");
        assertThat(login.has("token")).isFalse();
        assertThat(login.get("enviadoPara").asText()).isEqualTo("c****r@petroangola.co.ao");
        assertThat(enviados).hasSize(1);
        assertThat(enviados.get(0).assunto()).isEqualTo("Código de acesso KIXIMA");

        MvcResult res = pedir("/api/auth/2fa/verify", null,
                Map.of("challenge", login.get("challenge").asText(), "code", enviados.get(0).codigo()));
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(res).get("token").asText()).isNotBlank();
    }

    /**
     * Esta regressão já aconteceu: o travão de reenvio (60s) era aplicado também
     * ao envio automático do login. Quem acabasse de ativar a 2FA e voltasse a
     * entrar no minuto seguinte via o LOGIN falhar com "aguarde 47 segundos", sem
     * forma nenhuma de entrar.
     */
    @Test
    void entrarLogoASeguirAAtivarNaoETravado() throws Exception {
        ativar();
        JsonNode login = entrar();
        assertThat(login.get("requires2fa").asBoolean()).isTrue();
        assertThat(login.get("challenge").asText()).isNotBlank();
    }

    @Test
    void entrarDuasVezesSeguidasReaproveitaOCodigoSemEnviarOutro() throws Exception {
        ativar();
        entrar();
        assertThat(enviados).hasSize(1);

        JsonNode segundo = entrar();
        assertThat(segundo.get("requires2fa").asBoolean()).isTrue();
        assertThat(enviados).hasSize(1);          // não se mandou um segundo email
        assertThat(segundo.get("reaproveitado").asBoolean()).isTrue();

        // E o código que a pessoa já tem na caixa continua a servir.
        MvcResult res = pedir("/api/auth/2fa/verify", null,
                Map.of("challenge", segundo.get("challenge").asText(), "code", enviados.get(0).codigo()));
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void umCodigoErradoNaoEntra() throws Exception {
        ativar();
        JsonNode login = entrar();
        MvcResult res = pedir("/api/auth/2fa/verify", null,
                Map.of("challenge", login.get("challenge").asText(), "code", "000000"));
        assertThat(res.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void daParaPedirOutroCodigoSemRecomecarOLogin() throws Exception {
        ativar();
        JsonNode login = entrar();
        conta().setMfaCodeEnviadoEm(null);

        MvcResult re = pedir("/api/auth/2fa/reenviar", null, Map.of("challenge", login.get("challenge").asText()));
        assertThat(re.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(re).get("enviadoPara").asText()).isEqualTo("c****r@petroangola.co.ao");
        assertThat(enviados).hasSize(2);

        // O código antigo deixou de servir; só o novo entra.
        MvcResult antigo = pedir("/api/auth/2fa/verify", null,
                Map.of("challenge", login.get("challenge").asText(), "code", enviados.get(0).codigo()));
        assertThat(antigo.getResponse().getStatus()).isEqualTo(401);
        MvcResult novo = pedir("/api/auth/2fa/verify", null,
                Map.of("challenge", login.get("challenge").asText(), "code", enviados.get(1).codigo()));
        assertThat(novo.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void semUmDesafioValidoNaoSePedeCodigoNenhum() throws Exception {
        MvcResult res = pedir("/api/auth/2fa/reenviar", null, Map.of("challenge", "x".repeat(40)));
        assertThat(res.getResponse().getStatus()).isEqualTo(401);
        assertThat(enviados).isEmpty();
    }

    // --- Quem usa a app de autenticação continua igual ----------------------

    @Test
    void oLoginPedeOCodigoDaAppNaoDoEmail() throws Exception {
        String token = entrar().get("token").asText();
        String secret = json(pedir("/api/auth/2fa/setup", token, null)).get("secret").asText();
        pedir("/api/auth/2fa/enable", token, Map.of("code", totpService.totp(secret)));

        JsonNode login = entrar();
        assertThat(login.get("metodo").asText()).isEqualTo("TOTP");
        assertThat(enviados).isEmpty();   // nada foi enviado por email

        MvcResult res = pedir("/api/auth/2fa/verify", null,
                Map.of("challenge", login.get("challenge").asText(), "code", totpService.totp(secret)));
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void eNaoLheEOferecidoReenvioPorEmail() throws Exception {
        String token = entrar().get("token").asText();
        String secret = json(pedir("/api/auth/2fa/setup", token, null)).get("secret").asText();
        pedir("/api/auth/2fa/enable", token, Map.of("code", totpService.totp(secret)));

        JsonNode login = entrar();
        MvcResult re = pedir("/api/auth/2fa/reenviar", null, Map.of("challenge", login.get("challenge").asText()));
        assertThat(re.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        assertThat(mensagemDeErro(re)).containsPattern("(?i)app de autenticação");
    }
}
