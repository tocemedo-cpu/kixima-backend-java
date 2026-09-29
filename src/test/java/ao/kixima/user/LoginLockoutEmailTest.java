package ao.kixima.user;

import ao.kixima.common.error.ValidationException;
import ao.kixima.notification.EmailDispatchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Espelha os casos de email de backend/tests/login-lockout.test.js — o
 * titular é avisado quando a conta bloqueia (é a única parte que apanha um
 * ataque que ACERTA), não mais do que uma vez por hora, e uma falha no envio
 * nunca desfaz o bloqueio: o bloqueio é a proteção, o email é a cortesia.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LoginLockoutEmailTest {

    private static final String EMAIL = "comprador@petroangola.co.ao";
    private static final String SENHA = "Kixima@123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @MockBean
    private EmailDispatchService emailDispatchService;

    private MvcResult tentar(String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType("application/json")
                .content(objectMapper.writeValueAsString(Map.of("email", EMAIL, "password", password)))).andReturn();
    }

    private MvcResult tentar() throws Exception {
        return tentar("errada-de-propósito");
    }

    private User conta() {
        return userRepository.findByEmail(EMAIL).orElseThrow();
    }

    @Test
    void oTitularEAvisadoEAUnicaParteQueApanhaUmAtaqueQueAcerta() throws Exception {
        for (int i = 0; i < LoginAttemptService.LIMIAR; i += 1) tentar();

        // Persistido de facto (o Node faz um UPDATE por falha) — não só na entidade em memória.
        entityManager.flush();
        Map<String, Object> estado = jdbcTemplate.queryForMap(
                "SELECT falhas_seguidas, bloqueado_ate, aviso_bloqueio_em FROM users WHERE email = ?", EMAIL);
        assertThat(estado.get("falhas_seguidas")).isEqualTo(LoginAttemptService.LIMIAR);
        assertThat(estado.get("bloqueado_ate")).isNotNull();
        assertThat(estado.get("aviso_bloqueio_em")).isNotNull();

        ArgumentCaptor<String> corpo = ArgumentCaptor.forClass(String.class);
        verify(emailDispatchService, times(1)).enviarDireto(eq(EMAIL), eq("Tentativas de entrada na sua conta KIXIMA"), corpo.capture());
        assertThat(corpo.getValue())
                .startsWith("Olá ")
                .contains("Houve 5 tentativas seguidas de entrar na sua conta com a senha errada.")
                .contains("Por segurança, a conta ficou bloqueada durante 1 minuto(s).")
                .contains("\"Esqueci-me da senha\" para definir uma nova.")
                .contains("SE NÃO FOI VOCÊ: alguém sabe o seu email e está a adivinhar a senha.")
                .endsWith("Equipe Kixima.");
    }

    @Test
    void naoSeAvisaOTitularMaisDoQueUmaVezPorHora() throws Exception {
        for (int i = 0; i < LoginAttemptService.LIMIAR; i += 1) tentar();
        verify(emailDispatchService, times(1)).enviarDireto(eq(EMAIL), anyString(), anyString());

        // O bloqueio de 1 minuto expirou; a sexta falha bloqueia outra vez (2 min)
        // — mas o aviso foi há menos de uma hora, por isso não se repete.
        conta().setBloqueadoAte(Instant.now().minusSeconds(1));
        tentar();
        User u = conta();
        assertThat(u.getFalhasSeguidas()).isEqualTo(LoginAttemptService.LIMIAR + 1);
        assertThat(u.getBloqueadoAte()).isAfter(Instant.now());
        verify(emailDispatchService, times(1)).enviarDireto(eq(EMAIL), anyString(), anyString());

        // Passada uma hora desde o último aviso, volta a avisar.
        u.setAvisoBloqueioEm(Instant.now().minusSeconds(61 * 60));
        u.setBloqueadoAte(Instant.now().minusSeconds(1));
        tentar();
        verify(emailDispatchService, times(2)).enviarDireto(eq(EMAIL), anyString(), anyString());
    }

    @Test
    void falharOEnvioNaoDesfazOBloqueio() throws Exception {
        // EMAIL_PROVIDER=console: enviarDireto recusa por extenso (422) — fica no log, o bloqueio mantém-se.
        doThrow(new ValidationException("EMAIL_PROVIDER=console — nada é enviado. Defina EMAIL_PROVIDER=brevo e BREVO_API_KEY."))
                .when(emailDispatchService).enviarDireto(anyString(), anyString(), anyString());

        for (int i = 0; i < LoginAttemptService.LIMIAR; i += 1) tentar();

        MvcResult res = tentar(SENHA);
        assertThat(res.getResponse().getStatus()).isEqualTo(401);
        assertThat(res.getResponse().getContentAsString()).containsPattern("bloqueada durante \\d+ minuto");
        // O carimbo do aviso é posto ANTES do envio (como no Node) — mesmo falhado, conta como tentativa de aviso.
        assertThat(conta().getAvisoBloqueioEm()).isNotNull();
    }
}
