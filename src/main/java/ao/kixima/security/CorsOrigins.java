package ao.kixima.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Espelha backend/src/config/cors.js — a allow-list de origens aceites em
 * CORS, num sítio só, para o REST (SecurityConfig) e o tempo real
 * (WebSocketConfig) nunca mais divergirem: no Node cada um tinha a sua
 * cópia da lógica e a origem do Capacitor foi acrescentada a uma e esquecida
 * na outra (login a funcionar no Android, chat mudo).
 *
 * <ul>
 *   <li>{@link #allowList()} — APP_URL + origens fixas do Capacitor + CORS_ORIGINS.</li>
 *   <li>{@link #origin(String)} — a decisão por pedido, no formato do callback
 *       {@code origin(origem, cb)} do pacote {@code cors}: sem Origin (curl,
 *       same-origin) aceita sempre; em desenvolvimento/teste aceita qualquer
 *       origem; caso contrário só a allow-list. Uma origem não autorizada não
 *       é um erro — apenas não é autorizada; o browser bloqueia do lado dele.</li>
 * </ul>
 */
@Component
public class CorsOrigins {

    /**
     * Origens fixas do WebView do Capacitor (Android/iOS) — não são um domínio
     * de terceiros, são o esquema do próprio runtime nativo: o pedido sai
     * sempre com Origin: https://localhost ou capacitor://localhost, nunca
     * com o domínio kixima.net.
     */
    public static final List<String> ORIGENS_CAPACITOR = List.of("https://localhost", "capacitor://localhost");

    private final String appUrl;
    private final String corsOrigins;
    private final boolean desenvolvimentoOuTeste;

    @Autowired
    public CorsOrigins(@Value("${kixima.app-url:}") String appUrl,
                       @Value("${kixima.cors.origins:}") String corsOrigins,
                       Environment environment) {
        // NODE_ENV=development|test no Node; aqui os perfis "dev"/"test" (e
        // nenhum perfil activo, que é o equivalente ao NODE_ENV por definir).
        this(appUrl, corsOrigins, environment.acceptsProfiles(Profiles.of("dev", "test", "default")));
    }

    CorsOrigins(String appUrl, String corsOrigins, boolean desenvolvimentoOuTeste) {
        this.appUrl = appUrl == null ? "" : appUrl.trim();
        this.corsOrigins = corsOrigins == null ? "" : corsOrigins;
        this.desenvolvimentoOuTeste = desenvolvimentoOuTeste;
    }

    /** Espelha `allowList()` — APP_URL, as origens do Capacitor e CORS_ORIGINS (separadas por vírgula), sem vazios. */
    public List<String> allowList() {
        List<String> lista = new ArrayList<>();
        lista.add(appUrl);
        lista.addAll(ORIGENS_CAPACITOR);
        lista.addAll(Arrays.asList(corsOrigins.split(",")));
        return lista.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * `config.isDevelopment || config.isTest` — em desenvolvimento/teste
     * aceita-se qualquer origem (o Vite está noutra porta). É o que permite ao
     * STOMP, que só aceita listas estáticas, usar o padrão "*" nesses perfis.
     */
    public boolean aceitaQualquerOrigem() {
        return desenvolvimentoOuTeste;
    }

    /** Espelha `origin(origemDoPedido, cb)` — {@code true} quando o pedido é autorizado. */
    public boolean origin(String origemDoPedido) {
        if (origemDoPedido == null || origemDoPedido.isEmpty()) return true; // same-origin, curl
        if (desenvolvimentoOuTeste) return true;
        return allowList().contains(origemDoPedido);
    }
}
