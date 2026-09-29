package ao.kixima.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Espelha tests/rate-limit.test.js e tests/rate-limit-forgot-password.test.js.
 * O Node desliga os limitadores em NODE_ENV=test e testa a configuração real
 * por `_options`; aqui constrói-se o filtro LIGADO, com máximos pequenos, e
 * exercita-se a configuração real (regras, chave, skipSuccessfulRequests,
 * resposta 429) sem servidor.
 */
class RateLimitFilterTest {

    private static final String SEGREDO = "teste-jwt-secret-com-pelo-menos-32-caracteres-000000";
    private final JwtService jwt = new JwtService(SEGREDO, "1d");
    private final SessionCookieUtil cookies = new SessionCookieUtil(jwt, "test");
    private final ObjectMapper om = new ObjectMapper();

    private RateLimitFilter filtro(RateLimitFilter.Limites limites) {
        return new RateLimitFilter(jwt, cookies, om, true, true, limites);
    }

    private static MockHttpServletRequest pedido(String metodo, String caminho, String ip) {
        MockHttpServletRequest r = new MockHttpServletRequest(metodo, caminho);
        r.setRequestURI(caminho);
        r.setRemoteAddr(ip);
        return r;
    }

    private static int correr(RateLimitFilter f, MockHttpServletRequest req, int estadoDaRota) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain cadeia = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest rq, jakarta.servlet.http.HttpServletResponse rs) {
                rs.setStatus(estadoDaRota);
            }
        });
        f.doFilter(req, res, cadeia);
        return res.getStatus();
    }

    // --- A que endpoints se aplica o limite apertado --------------------------------

    @Test
    void authMeSemSessaoDevolve401ENao429PorMuitasVezesQueSeChame() throws Exception {
        RateLimitFilter f = filtro(new RateLimitFilter.Limites(600, 3, 10, 30, 60, 10, 10));
        int ultimo = 0;
        for (int i = 0; i < 40; i++) ultimo = correr(f, pedido("GET", "/api/auth/me", "10.0.0.7"), 401);
        assertThat(ultimo).isEqualTo(401);
        // …enquanto o login, esse, esgota o orçamento apertado.
        for (int i = 0; i < 3; i++) assertThat(correr(f, pedido("POST", "/api/auth/login", "10.0.0.7"), 401)).isEqualTo(401);
        assertThat(correr(f, pedido("POST", "/api/auth/login", "10.0.0.7"), 401)).isEqualTo(429);
    }

    @Test
    void quemAcertaNaoGastaOrcamentoNenhumMasQuemFalhaGasta() throws Exception {
        RateLimitFilter f = filtro(new RateLimitFilter.Limites(600, 2, 10, 30, 60, 10, 10));
        for (int i = 0; i < 10; i++) assertThat(correr(f, pedido("POST", "/api/auth/login", "10.0.0.8"), 200)).isEqualTo(200);
        assertThat(correr(f, pedido("POST", "/api/auth/login", "10.0.0.8"), 401)).isEqualTo(401);
        assertThat(correr(f, pedido("POST", "/api/auth/login", "10.0.0.8"), 401)).isEqualTo(401);
        assertThat(correr(f, pedido("POST", "/api/auth/login", "10.0.0.8"), 401)).isEqualTo(429);
    }

    // --- forgotPasswordLimiter — configuração ----------------------------------------

    @Test
    void forgotPasswordNaoHerdaSkipSuccessfulRequestsEEUmLimitadorProprio() throws Exception {
        RateLimitFilter f = filtro(new RateLimitFilter.Limites(600, 60, 2, 30, 60, 10, 10));
        Optional<RateLimitFilter.Regra> auth = f.regras().stream().filter(r -> r.nome().equals("auth")).findFirst();
        Optional<RateLimitFilter.Regra> forgot = f.regras().stream().filter(r -> r.nome().equals("forgot-password")).findFirst();
        assertThat(auth).get().extracting(RateLimitFilter.Regra::ignorarSucessos).isEqualTo(true);
        assertThat(forgot).get().extracting(RateLimitFilter.Regra::ignorarSucessos).isEqualTo(false);
        assertThat(forgot.get()).isNotSameAs(auth.get());
        // A rota devolve sempre 200 (anti-enumeração) — e mesmo assim cada pedido conta.
        assertThat(correr(f, pedido("POST", "/api/auth/forgot-password", "10.0.0.9"), 200)).isEqualTo(200);
        assertThat(correr(f, pedido("POST", "/api/auth/forgot-password", "10.0.0.9"), 200)).isEqualTo(200);
        assertThat(correr(f, pedido("POST", "/api/auth/forgot-password", "10.0.0.9"), 200)).isEqualTo(429);
    }

    // --- A quem se conta cada pedido ---------------------------------------------------

    @Test
    void semSessaoContaAoEndereco() {
        RateLimitFilter f = filtro(new RateLimitFilter.Limites(600, 60, 10, 30, 60, 10, 10));
        assertThat(f.utilizadorDoToken(pedido("GET", "/api/catalog", "10.0.0.7"))).isNull();
        assertThat(f.ipDoCliente(pedido("GET", "/api/catalog", "10.0.0.7"))).isEqualTo("10.0.0.7");
    }

    @Test
    void comSessaoContaAPessoaEDuasPessoasNoMesmoEnderecoTemBaldesDiferentes() {
        RateLimitFilter f = filtro(new RateLimitFilter.Limites(600, 60, 10, 30, 60, 10, 10));
        MockHttpServletRequest ana = pedido("GET", "/api/catalog", "10.0.0.7");
        ana.setCookies(new Cookie(SessionCookieUtil.NOME, jwt.signAccessToken("ana", PersonaRole.COMPRADOR, "c1", 0)));
        MockHttpServletRequest bruno = pedido("GET", "/api/catalog", "10.0.0.7");
        bruno.addHeader("Authorization", "Bearer " + jwt.signAccessToken("bruno", PersonaRole.COMPRADOR, "c1", 0));
        assertThat(f.utilizadorDoToken(ana)).isEqualTo("ana");
        assertThat(f.utilizadorDoToken(bruno)).isEqualTo("bruno");
    }

    @Test
    void umTokenForjadoNaoGastaOOrcamentoDeOutraPessoa() {
        RateLimitFilter f = filtro(new RateLimitFilter.Limites(600, 60, 10, 30, 60, 10, 10));
        String falso = new JwtService("segredo-errado-mas-com-trinta-e-dois-caracteres!", "1d")
                .signAccessToken("vitima", PersonaRole.COMPRADOR, "c1", 0);
        MockHttpServletRequest req = pedido("GET", "/api/catalog", "10.0.0.9");
        req.addHeader("Authorization", "Bearer " + falso);
        assertThat(f.utilizadorDoToken(req)).isNull();
    }

    @Test
    void oEnderecoEOUltimoSaltoDeXForwardedForEIPv6AgregaA56() {
        RateLimitFilter f = filtro(new RateLimitFilter.Limites(600, 60, 10, 30, 60, 10, 10));
        MockHttpServletRequest req = pedido("GET", "/api/catalog", "127.0.0.1");
        req.addHeader("X-Forwarded-For", "203.0.113.5, 198.51.100.7");
        assertThat(f.ipDoCliente(req)).isEqualTo("198.51.100.7");
        assertThat(RateLimitFilter.normalizarIp("2001:db8:1234:5678:abcd::1"))
                .isEqualTo(RateLimitFilter.normalizarIp("2001:db8:1234:56ff::9"));
        assertThat(RateLimitFilter.normalizarIp("2001:db8:1234:5678::1"))
                .isNotEqualTo(RateLimitFilter.normalizarIp("2001:db8:1234:9900::1"));
    }

    // --- Resposta e cabeçalhos ----------------------------------------------------------

    @Test
    void o429TemOMesmoCorpoDoNodeEOsCabecalhosDraft7() throws Exception {
        RateLimitFilter f = filtro(new RateLimitFilter.Limites(600, 60, 10, 30, 60, 1, 10));
        MockHttpServletResponse ok = new MockHttpServletResponse();
        f.doFilter(pedido("POST", "/api/feedback", "10.0.0.3"), ok, new MockFilterChain());
        assertThat(ok.getHeader("RateLimit-Policy")).isEqualTo("1;w=900");
        assertThat(ok.getHeader("RateLimit")).startsWith("limit=1, remaining=0, reset=");

        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(pedido("POST", "/api/feedback", "10.0.0.3"), res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isNotBlank();
        var corpo = om.readTree(res.getContentAsString());
        assertThat(corpo.at("/error/code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(corpo.at("/error/message").asText()).isEqualTo("Demasiados envios. Tente novamente mais tarde.");
    }

    @Test
    void oChatContaPorMinutoEPorPessoaEOLimiteGeralTambemConta() throws Exception {
        RateLimitFilter f = filtro(new RateLimitFilter.Limites(4, 60, 10, 30, 2, 10, 10));
        assertThat(f.regras().stream().filter(r -> r.nome().equals("chat")).findFirst().get().janela()).isEqualTo(Duration.ofMinutes(1));
        String token = jwt.signAccessToken("ana", PersonaRole.COMPRADOR, "c1", 0);
        MockHttpServletRequest m1 = pedido("POST", "/api/conversations/abc/messages", "10.0.0.1");
        m1.addHeader("Authorization", "Bearer " + token);
        assertThat(correr(f, m1, 201)).isEqualTo(201);
        assertThat(correr(f, m1, 201)).isEqualTo(201);
        assertThat(correr(f, m1, 201)).isEqualTo(429); // 2/min de chat esgotados — e este também contou no limite geral
        // Um GET qualquer da mesma pessoa: já gastou 3 do limite geral (os três POST); este é o 4.º, o próximo é 429.
        MockHttpServletRequest g = pedido("GET", "/api/notifications", "10.0.0.1");
        g.addHeader("Authorization", "Bearer " + token);
        assertThat(correr(f, g, 200)).isEqualTo(200);
        assertThat(correr(f, g, 200)).isEqualTo(429);
    }

    @Test
    void desligadoDeixaTudoPassar() throws Exception {
        RateLimitFilter f = new RateLimitFilter(jwt, cookies, om, false, true, new RateLimitFilter.Limites(1, 1, 1, 1, 1, 1, 1));
        for (int i = 0; i < 5; i++) assertThat(correr(f, pedido("POST", "/api/auth/login", "10.0.0.7"), 401)).isEqualTo(401);
    }
}
