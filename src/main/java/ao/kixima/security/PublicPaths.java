package ao.kixima.security;

import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caminhos que NÃO passam por {@link AuthenticationFilter} — espelha, rota a
 * rota, quais têm `authenticate` composto no Node (a maioria) e quais não
 * (login, forgot/reset-password, 2fa/verify, 2fa/reenviar — ver
 * authRoutes.js). Cada marco (M2+) acrescenta aqui os caminhos públicos do
 * seu próprio domínio à medida que os controllers são portados — nunca ao
 * contrário: por omissão, um caminho novo é PROTEGIDO, tal como no Node um
 * router novo exige `authenticate` a menos que se decida o oposto
 * explicitamente.
 *
 * Entradas são MÉTODO+caminho, tal como o Node ({@code router.get}/
 * {@code router.post} num mesmo caminho podem ter exigências diferentes de
 * `authenticate`) — {@link #adicionar(String)} (sem método) regista para
 * TODOS os métodos, para os casos (a maioria) em que isso não importa.
 */
@Component
public class PublicPaths {

    private static final String QUALQUER_METODO = "*";

    private record Entrada(String metodo, String pathPattern) {
    }

    private final Set<Entrada> entradas = ConcurrentHashMap.newKeySet();
    private final AntPathMatcher matcher = new AntPathMatcher();

    public PublicPaths() {
        // authRoutes.js — só estas cinco rotas não têm `authenticate`.
        adicionar("/api/auth/login");
        adicionar("/api/auth/forgot-password");
        adicionar("/api/auth/reset-password");
        adicionar("/api/auth/2fa/verify");
        adicionar("/api/auth/2fa/reenviar");
        adicionar("/actuator/health");
        // app.js — sondas de saúde, sem sessão.
        adicionar("GET", "/health");
        adicionar("GET", "/ready");
        // uploadsRoutes.js — `optionalAuthenticate`, não `authenticate`: o mesmo
        // filtro que decide "público" também popula CurrentUserHolder quando um
        // token válido vem no pedido (só os ramos de token AUSENTE/INVÁLIDO é que
        // seguem sem utilizador) — por isso marcar como público aqui já reproduz
        // "autenticação opcional", sem precisar de um segundo modo no filtro.
        adicionar("/api/uploads/*");
        // companyRoutes.js — resolução/aceitação de convite são públicas: o
        // token assinado (ver InviteController/InviteService) é a própria
        // autorização, tal como authenticate não está composto nestas duas
        // rotas no Node.
        // Cadastro público de empresa (onboarding) — companyRoutes.js, antes de authenticate.
        adicionar("POST", "/api/companies/register");
        adicionar("/api/companies/invite/*");
        adicionar("/api/companies/invite/*/accept");
        // Convite de assessor ADMIN_SISTEMA (adminRoutes.js) — o token opaco é a autorização.
        adicionar("GET", "/api/admin/invite/*");
        adicionar("POST", "/api/admin/invite/*/accept");
        // supplierDevRoutes.js — candidatura e a sua consulta pública são
        // abertas; GET /requests (listagem do Admin do Sistema) partilha o
        // MESMO caminho de POST /requests (candidatar) mas fica de fora —
        // por isso os métodos são registados em separado aqui, não o
        // caminho inteiro.
        adicionar("GET", "/api/supplier-development/fee");
        adicionar("POST", "/api/supplier-development/requests");
        // Callback do microserviço de integração ERP — protegido pela assinatura HMAC, não por sessão.
        adicionar("POST", "/api/integration/callback");
        adicionar("GET", "/api/supplier-development/requests/*/track");
        // publicRoutes.js — a parede pública de avaliações, só leitura.
        adicionar("GET", "/api/public/feedback");
        adicionar("GET", "/api/public/stats");
        // Tabela de planos e preços — pública (planosRoutes.js).
        adicionar("GET", "/api/planos");
        // Callback dos canais de pagamento automático — quem chama é o gateway, não um utilizador (webhookPagamentoRoutes.js).
        adicionar("POST", "/api/webhooks/pagamento/*");
        // app.js — rota pública direta (fora de qualquer router com `authenticate`).
        adicionar("GET", "/api/retencao");
        // API externa de catálogo: autenticada por CHAVE (Bearer kxm_...), não por sessão — ver ApiCatalogoController.
        adicionar("/api/v1/catalogo");
        adicionar("/api/v1/catalogo/*");
        // realtimeService.js — o upgrade HTTP do WebSocket passa sem token: a
        // autenticação é no CONNECT STOMP (io.use(autenticarSocket) no Node),
        // porque o Bearer do Capacitor só pode vir nesse frame, nunca no upgrade.
        adicionar("/ws/**");
    }

    /** Regista um caminho como público para TODOS os métodos HTTP. */
    public void adicionar(String pathPattern) {
        adicionar(QUALQUER_METODO, pathPattern);
    }

    /** Regista um caminho como público só para o método indicado (ex.: {@code "GET"}, {@code "POST"}). */
    public void adicionar(String metodo, String pathPattern) {
        entradas.add(new Entrada(metodo, pathPattern));
    }

    public boolean ePublico(String metodo, String path) {
        return entradas.stream().anyMatch(e ->
                (e.metodo().equals(QUALQUER_METODO) || e.metodo().equalsIgnoreCase(metodo)) && matcher.match(e.pathPattern(), path));
    }
}
