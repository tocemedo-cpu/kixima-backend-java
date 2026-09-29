package ao.kixima.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Espelha backend/src/config/csp.js — a Content-Security-Policy que o helmet
 * emite no Node, com os anfitriões de armazenamento e do Sentry acrescentados.
 *
 * PORQUE É QUE ISTO EXISTE EM VEZ DA POLÍTICA POR OMISSÃO DO HELMET (ver o
 * comentário por extenso em csp.js): a política por omissão só permite
 * imagens da própria origem, e as fotos do catálogo vêm do bucket. A avaria
 * não aparece em desenvolvimento nem dá erro no servidor — é o browser que
 * recusa. Por isso os anfitriões permitidos são DERIVADOS da configuração de
 * armazenamento, nunca escritos à mão.
 *
 * A base é a lista literal de `helmet.contentSecurityPolicy.getDefaultDirectives()`
 * do helmet 7.2.0 (a versão resolvida em backend/package-lock.json), pela
 * mesma ordem, para o cabeçalho sair byte a byte igual ao do Node.
 */
@Component
public class ContentSecurityPolicy {

    /** `helmet.contentSecurityPolicy.getDefaultDirectives()` — helmet 7.2.0, por esta ordem. */
    public static final Map<String, List<String>> DIRECTIVAS_BASE_HELMET;

    static {
        Map<String, List<String>> base = new LinkedHashMap<>();
        base.put("default-src", List.of("'self'"));
        base.put("base-uri", List.of("'self'"));
        base.put("font-src", List.of("'self'", "https:", "data:"));
        base.put("form-action", List.of("'self'"));
        base.put("frame-ancestors", List.of("'self'"));
        base.put("img-src", List.of("'self'", "data:"));
        base.put("object-src", List.of("'none'"));
        base.put("script-src", List.of("'self'"));
        base.put("script-src-attr", List.of("'none'"));
        base.put("style-src", List.of("'self'", "https:", "'unsafe-inline'"));
        base.put("upgrade-insecure-requests", List.of());
        DIRECTIVAS_BASE_HELMET = Collections.unmodifiableMap(base);
    }

    private final String publicUrl;
    private final String endpoint;
    private final String bucket;
    private final String region;
    private final String sentryFrontendDsn;
    private final String sentryDsn;

    public ContentSecurityPolicy(@Value("${kixima.storage.public-url:}") String publicUrl,
                                 @Value("${kixima.storage.endpoint:}") String endpoint,
                                 @Value("${kixima.storage.bucket:}") String bucket,
                                 @Value("${kixima.storage.region:}") String region,
                                 @Value("${kixima.sentry.frontend-dsn:}") String sentryFrontendDsn,
                                 @Value("${sentry.dsn:}") String sentryDsn) {
        this.publicUrl = publicUrl;
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.region = region;
        this.sentryFrontendDsn = sentryFrontendDsn;
        this.sentryDsn = sentryDsn;
    }

    private static boolean vazio(String v) {
        return v == null || v.isBlank();
    }

    /**
     * A origem (esquema + anfitrião) de um URL, ou null se não der para ler —
     * o mesmo que `${u.protocol}//${u.host}` do `new URL()` do Node: só a
     * origem, sem caminho, com o porto apenas quando não é o do esquema.
     */
    public static String origemDe(String url) {
        if (vazio(url)) return null;
        try {
            URI u = new URI(url.trim());
            if (u.getScheme() == null || u.getHost() == null) return null;
            String esquema = u.getScheme().toLowerCase(Locale.ROOT);
            String host = u.getHost().toLowerCase(Locale.ROOT);
            int porto = u.getPort();
            boolean portoPorOmissao = porto == -1
                    || ("https".equals(esquema) && porto == 443)
                    || ("http".equals(esquema) && porto == 80);
            return esquema + "://" + host + (portoPorOmissao ? "" : ":" + porto);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * De onde é legítimo carregar imagens: o URL público E o endpoint (são
     * caminhos diferentes no código e ambos acabam em {@code <img src>}), mais
     * o anfitrião AWS reconstruído a partir do bucket e da região quando não
     * há endpoint próprio (ver StorageService.publicUrlFor).
     */
    public List<String> origensDeImagem() {
        List<String> origens = new ArrayList<>();
        origens.add(origemDe(publicUrl));
        origens.add(origemDe(endpoint));
        if (vazio(endpoint) && !vazio(bucket) && !vazio(region)) {
            origens.add("https://" + bucket + ".s3." + region + ".amazonaws.com");
        }
        return semDuplicadosNemNulos(origens);
    }

    /**
     * Para onde o browser pode abrir ligações — o anfitrião do DSN do Sentry,
     * para o Sentry do frontend não ficar calado pela CSP. Usa-se o DSN do
     * backend quando SENTRY_FRONTEND_DSN não está definido.
     */
    public List<String> origensDeLigacao() {
        List<String> origens = new ArrayList<>();
        origens.add(origemDe(sentryFrontendDsn));
        origens.add(origemDe(sentryDsn));
        return semDuplicadosNemNulos(origens);
    }

    private static List<String> semDuplicadosNemNulos(List<String> origens) {
        LinkedHashSet<String> unicas = new LinkedHashSet<>();
        origens.stream().filter(Objects::nonNull).forEach(unicas::add);
        return List.copyOf(unicas);
    }

    /** As directivas: parte-se das do helmet e acrescenta-se só o que é preciso — `directivas(base)` em csp.js. */
    public Map<String, List<String>> directivas(Map<String, List<String>> base) {
        List<String> imagens = origensDeImagem();
        List<String> ligacoes = origensDeLigacao();
        Map<String, List<String>> d = new LinkedHashMap<>(base);
        d.put("img-src", concat(List.of("'self'", "data:", "blob:"), imagens));
        d.put("connect-src", concat(List.of("'self'"), ligacoes));
        // O comprovativo de pagamento e os documentos de credenciamento são PDFs
        // que o revisor abre a partir do bucket. Sem isto o botão "Abrir" não faz nada.
        d.put("frame-src", concat(List.of("'self'"), imagens));
        return d;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> r = new ArrayList<>(a);
        r.addAll(b);
        return List.copyOf(r);
    }

    /**
     * O valor do cabeçalho, serializado como o helmet o faz: cada directiva é
     * o nome seguido dos valores separados por espaço (só o nome quando não
     * tem valores), e as directivas são unidas por ";" sem espaço.
     */
    public String cabecalho() {
        StringBuilder sb = new StringBuilder();
        directivas(DIRECTIVAS_BASE_HELMET).forEach((nome, valores) -> {
            if (sb.length() > 0) sb.append(';');
            sb.append(nome);
            for (String v : valores) sb.append(' ').append(v);
        });
        return sb.toString();
    }
}
