package ao.kixima.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Porta backend/src/middleware/rateLimit.js e os limitadores locais de
 * feedbackRoutes.js / supplierDevRoutes.js — um filtro só, antes da
 * autenticação, com as mesmas regras, os mesmos máximos (variáveis de
 * ambiente incluídas), a mesma chave (à PESSOA quando há sessão válida, ao
 * endereço quando não há — ver o comentário longo do Node sobre escritórios
 * atrás de um NAT), a mesma resposta 429 {@code {error:{code:'RATE_LIMITED'}}}
 * e os cabeçalhos draft-7 ({@code RateLimit}, {@code RateLimit-Policy}).
 * <p>
 * Tal como no Node, cada pedido passa por TODAS as regras que lhe cabem (o
 * limite geral da API e o do endpoint sensível contam ambos), e em ambiente
 * de teste o filtro está desligado ({@code kixima.rate-limit.enabled=false}).
 * Bucket4j com janela fixa por chave, em memória, por instância — o mesmo
 * alcance do MemoryStore do express-rate-limit.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String MENSAGEM = "Demasiados pedidos. Tente novamente mais tarde.";
    private static final Duration QUINZE_MIN = Duration.ofMinutes(15);

    /** Uma regra do rateLimit.js: onde se aplica, quanto permite, a quem conta, e se os sucessos devolvem o orçamento. */
    public record Regra(String nome, int max, Duration janela, boolean porUtilizador, boolean ignorarSucessos, String mensagem,
                        List<String> alvos) {
        boolean cabe(String metodo, String caminho) {
            for (String alvo : alvos) {
                String[] partes = alvo.split(" ", 2);
                String m = partes.length == 2 ? partes[0] : null;
                String padrao = partes.length == 2 ? partes[1] : partes[0];
                if ((m == null || m.equalsIgnoreCase(metodo)) && MATCHER.match(padrao, caminho)) return true;
            }
            return false;
        }
    }

    /** Os máximos configuráveis (as mesmas variáveis de ambiente do Node). */
    public record Limites(int api, int auth, int forgotPassword, int sensitive, int chat, int feedback, int candidaturas) {
    }

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final JwtService jwtService;
    private final SessionCookieUtil sessionCookieUtil;
    private final ObjectMapper objectMapper;
    private final boolean ativo;
    private final boolean confiarNoProxy;
    private final List<Regra> regras;
    private final Map<String, Bucket> baldes = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public RateLimitFilter(JwtService jwtService, SessionCookieUtil sessionCookieUtil, ObjectMapper objectMapper,
                           @Value("${kixima.rate-limit.enabled:true}") boolean ativo,
                           @Value("${kixima.rate-limit.trust-proxy:true}") boolean confiarNoProxy,
                           @Value("${kixima.rate-limit.api:600}") int api,
                           @Value("${kixima.rate-limit.auth:60}") int auth,
                           @Value("${kixima.rate-limit.forgot-password:10}") int forgotPassword,
                           @Value("${kixima.rate-limit.sensitive:30}") int sensitive,
                           @Value("${kixima.rate-limit.chat:60}") int chat,
                           @Value("${kixima.rate-limit.feedback:10}") int feedback,
                           @Value("${kixima.rate-limit.candidaturas:10}") int candidaturas) {
        this(jwtService, sessionCookieUtil, objectMapper, ativo, confiarNoProxy,
                new Limites(api, auth, forgotPassword, sensitive, chat, feedback, candidaturas));
    }

    public RateLimitFilter(JwtService jwtService, SessionCookieUtil sessionCookieUtil, ObjectMapper objectMapper,
                           boolean ativo, boolean confiarNoProxy, Limites l) {
        this.jwtService = jwtService;
        this.sessionCookieUtil = sessionCookieUtil;
        this.objectMapper = objectMapper;
        this.ativo = ativo;
        this.confiarNoProxy = confiarNoProxy;
        this.regras = regras(l);
    }

    /** A lista é explícita, como no app.js: quem acrescentar um endpoint que verifica credenciais tem de o pôr aqui. */
    static List<Regra> regras(Limites l) {
        return List.of(
                // Limite geral da API — trava inundação, não ataques (isso é o bloqueio por conta).
                new Regra("api", l.api(), QUINZE_MIN, true, false, MENSAGEM, List.of("/api/**")),
                // Só os endpoints que VERIFICAM credenciais — nunca /api/auth/me (ver rate-limit.test.js).
                new Regra("auth", l.auth(), QUINZE_MIN, false, true, MENSAGEM,
                        List.of("/api/auth/login/**", "/api/auth/2fa/verify/**", "/api/auth/reset-password/**")),
                // Limitador PRÓPRIO: a rota devolve sempre 200 (anti-enumeração), logo todo o pedido conta.
                new Regra("forgot-password", l.forgotPassword(), QUINZE_MIN, false, false, MENSAGEM, List.of("/api/auth/forgot-password/**")),
                new Regra("sensitive", l.sensitive(), QUINZE_MIN, false, false, MENSAGEM,
                        List.of("/api/companies/register/**", "/api/companies/invite/**", "/api/admin/invite/**")),
                // "Uma pessoa a escrever": 60/min por pessoa nos chats de suporte e comercial.
                new Regra("chat", l.chat(), Duration.ofMinutes(1), true, false, MENSAGEM,
                        List.of("POST /api/conversations/*/messages", "POST /api/support/tickets/*/messages")),
                new Regra("feedback", l.feedback(), QUINZE_MIN, false, false, "Demasiados envios. Tente novamente mais tarde.",
                        List.of("POST /api/feedback")),
                new Regra("candidaturas", l.candidaturas(), QUINZE_MIN, false, false, "Demasiadas candidaturas. Tente novamente mais tarde.",
                        List.of("POST /api/supplier-development/requests")));
    }

    public List<Regra> regras() {
        return regras;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!ativo) {
            chain.doFilter(request, response);
            return;
        }
        String metodo = request.getMethod();
        String caminho = request.getRequestURI();
        List<Regra> aplicaveis = new ArrayList<>(2);
        for (Regra r : regras) if (r.cabe(metodo, caminho)) aplicaveis.add(r);
        if (aplicaveis.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        String pessoa = null;
        String ip = null;
        List<Bucket> consumidos = new ArrayList<>(2);
        for (Regra r : aplicaveis) {
            String chave;
            if (r.porUtilizador()) {
                if (pessoa == null && ip == null) {
                    pessoa = utilizadorDoToken(request);
                    if (pessoa == null) ip = ipDoCliente(request);
                }
                chave = pessoa != null ? "u:" + pessoa : "ip:" + ip;
            } else {
                if (ip == null) ip = ipDoCliente(request);
                chave = "ip:" + ip;
            }
            Bucket balde = balde(r, chave);
            ConsumptionProbe sonda = balde.tryConsumeAndReturnRemaining(1);
            cabecalhos(response, r, sonda);
            if (!sonda.isConsumed()) {
                // Os baldes já gastos pelas regras anteriores ficam gastos — o Node também contava o pedido em cada uma.
                responder429(response, r, sonda);
                return;
            }
            if (r.ignorarSucessos()) consumidos.add(balde);
        }

        chain.doFilter(request, response);

        // skipSuccessfulRequests: quem acerta não gasta orçamento nenhum (statusCode < 400, como no express-rate-limit).
        if (!consumidos.isEmpty() && response.getStatus() < 400) {
            for (Bucket b : consumidos) b.addTokens(1);
        }
    }

    private Bucket balde(Regra r, String chave) {
        if (baldes.size() > 50_000) baldes.entrySet().removeIf(e -> e.getValue().getAvailableTokens() >= capacidadeDe(e.getKey()));
        return baldes.computeIfAbsent(r.nome() + "|" + chave, k -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(r.max()).refillIntervally(r.max(), r.janela()).build())
                .build());
    }

    private long capacidadeDe(String chaveDoBalde) {
        String nome = chaveDoBalde.substring(0, chaveDoBalde.indexOf('|'));
        for (Regra r : regras) if (r.nome().equals(nome)) return r.max();
        return Long.MAX_VALUE;
    }

    /** Cabeçalhos draft-7 do express-rate-limit (standardHeaders: 'draft-7'); a última regra aplicável prevalece, como lá. */
    private static void cabecalhos(HttpServletResponse res, Regra r, ConsumptionProbe sonda) {
        long reset = Math.max(1, sonda.getNanosToWaitForReset() / 1_000_000_000L);
        res.setHeader("RateLimit-Policy", r.max() + ";w=" + r.janela().toSeconds());
        res.setHeader("RateLimit", "limit=" + r.max() + ", remaining=" + Math.max(0, sonda.getRemainingTokens()) + ", reset=" + reset);
    }

    private void responder429(HttpServletResponse res, Regra r, ConsumptionProbe sonda) throws IOException {
        res.setStatus(429);
        res.setHeader("Retry-After", String.valueOf(Math.max(1, sonda.getNanosToWaitForRefill() / 1_000_000_000L)));
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write(objectMapper.writeValueAsString(Map.of("error", Map.of("code", "RATE_LIMITED", "message", r.mensagem()))));
    }

    // --- A quem se conta o pedido -------------------------------------------------

    /**
     * porUtilizadorOuIp: o `sub` do token (cookie primeiro, depois Bearer) SÓ se
     * a assinatura for válida — sem isso qualquer um escrevia um `sub` alheio e
     * gastava o orçamento de outra pessoa. Token inválido/expirado → anónimo.
     */
    String utilizadorDoToken(HttpServletRequest req) {
        String token = sessionCookieUtil.ler(req);
        if (token == null) {
            String header = req.getHeader("Authorization");
            if (header != null) {
                String[] partes = header.split(" ", 2);
                if (partes.length == 2 && !partes[1].isBlank()) token = partes[1];
            }
        }
        if (token == null) return null;
        try {
            String sub = jwtService.verifyRaw(token).getSubject();
            return sub == null || sub.isBlank() ? null : sub;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** `app.set('trust proxy', 1)`: o endereço é o último salto de X-Forwarded-For; IPv6 agregado a /56 como o ipKeyGenerator. */
    String ipDoCliente(HttpServletRequest req) {
        String ip = null;
        if (confiarNoProxy) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String[] saltos = xff.split(",");
                ip = saltos[saltos.length - 1].trim();
            }
        }
        if (ip == null || ip.isEmpty()) ip = req.getRemoteAddr();
        return normalizarIp(ip);
    }

    static String normalizarIp(String ip) {
        if (ip == null) return "desconhecido";
        if (!ip.contains(":")) return ip;
        try {
            byte[] b = InetAddress.getByName(ip).getAddress();
            if (b.length != 16) return ip;
            for (int i = 7; i < 16; i++) b[i] = 0; // /56
            return InetAddress.getByAddress(b).getHostAddress() + "/56";
        } catch (Exception e) {
            return ip;
        }
    }
}
