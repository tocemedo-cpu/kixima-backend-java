package ao.kixima.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Espelha o guardião de arranque de backend/src/config/env.js (o troço que
 * corre só com NODE_ENV=production): o processo RECUSA-SE a arrancar com um
 * JWT_SECRET fraco, com o armazenamento mal configurado, ou com CORS/cookie
 * de sessão inseguros.
 *
 * <ul>
 *   <li>JWT_SECRET: já era recusado vazio/"CHANGE_ME"; passou a recusar
 *       também os valores de exemplo do próprio .env.example
 *       ("troque-este-valor", ...) e qualquer segredo com menos de 32
 *       caracteres. Um deploy que copiasse o .env.example sem trocar o valor
 *       arrancava com um segredo público no repositório — dava para forjar um
 *       JWT de qualquer utilizador, incluindo Admin do Sistema.</li>
 *   <li>Storage: em modo 'local' (disco do contentor) os ficheiros carregados
 *       — documentos de credenciamento, comprovativos, cópias de segurança da
 *       própria base — desaparecem a cada deploy. Antes só ficava um erro no
 *       log e a app continuava a aceitar uploads que se perderiam; agora falha
 *       o arranque, tal como para o JWT_SECRET.</li>
 *   <li>CORS/cookie ({@link #verificarCorsECookie}): {@link ao.kixima.security.CorsOrigins}
 *       e {@link ao.kixima.security.SessionCookieUtil} já ficam restritos por
 *       omissão sem "dev"/"test" explícito — este guardião apanha o caso
 *       residual de {@code SPRING_PROFILES_ACTIVE=prod,dev} (os dois activos
 *       ao mesmo tempo) e o caso de nenhuma origem web estar configurada.</li>
 * </ul>
 *
 * Só existe no perfil {@code prod}: em desenvolvimento/teste os valores por
 * omissão de application.yml continuam a servir. A lógica em si está em
 * {@link #verificar}/{@link #verificarCorsECookie} (estáticas) para se
 * poder testar sem levantar o contexto.
 */
@Component
@Profile("prod")
public class ProducaoStartupGuard {

    /** Valores de exemplo que vivem em ficheiros VERSIONADOS (.env.example) — nunca segredos reais. */
    static final Set<String> JWT_SECRET_PLACEHOLDERS = Set.of("change_me", "troque-este-valor", "changeme", "change-me");
    /** ~256 bits em base64/hex — o mínimo razoável para HS256. */
    static final int JWT_SECRET_MIN_LENGTH = 32;

    public ProducaoStartupGuard(@Value("${kixima.auth.jwt-secret:}") String jwtSecret,
                                @Value("${kixima.storage.provider:local}") String storageProvider,
                                @Value("${kixima.storage.bucket:}") String bucket,
                                @Value("${kixima.storage.access-key:}") String accessKey,
                                @Value("${kixima.storage.secret-key:}") String secretKey,
                                @Value("${kixima.app-url:}") String appUrl,
                                @Value("${kixima.cors.origins:}") String corsOrigins,
                                Environment environment) {
        verificar(jwtSecret, storageProvider, bucket, accessKey, secretKey);
        // CORS/cookie: os dois derivam do MESMO sinal (CorsOrigins/SessionCookieUtil)
        // — "dev"/"test" activos ao lado de "prod" reabre-os por acidente (perfis
        // múltiplos são válidos no Spring: SPRING_PROFILES_ACTIVE=prod,dev). Verificado
        // aqui de novo, independentemente das duas classes, para o arranque falhar
        // já, em vez de um pedido real revelar a má configuração mais tarde.
        boolean permiteQualquerOrigem = environment.acceptsProfiles(Profiles.of("dev", "test"));
        boolean semOrigemConfigurada = (appUrl == null || appUrl.isBlank()) && (corsOrigins == null || corsOrigins.isBlank());
        verificarCorsECookie(permiteQualquerOrigem, semOrigemConfigurada, !permiteQualquerOrigem);
    }

    /** Espelha `jwtSecretFraco()` — o motivo, ou null quando o segredo serve. */
    static String jwtSecretFraco(String valor) {
        if (valor == null || valor.isEmpty()) return null; // vazio já cai em "em falta", não se repete aqui
        if (JWT_SECRET_PLACEHOLDERS.contains(valor.toLowerCase())) {
            return "ainda tem o valor de exemplo do .env.example — troque por um segredo real";
        }
        if (valor.length() < JWT_SECRET_MIN_LENGTH) {
            return "tem só " + valor.length() + " caracteres — use pelo menos " + JWT_SECRET_MIN_LENGTH + ", gerados aleatoriamente";
        }
        return null;
    }

    /** Espelha `config.storage.missing` — NOMES das variáveis obrigatórias em falta com S3 (uma string vazia conta como ausente). */
    static List<String> storageEmFalta(String provider, String bucket, String accessKey, String secretKey) {
        if (!"s3".equals(provider)) return List.of();
        List<String> falta = new ArrayList<>();
        if (vazio(bucket)) falta.add("STORAGE_BUCKET");
        if (vazio(accessKey)) falta.add("STORAGE_ACCESS_KEY");
        if (vazio(secretKey)) falta.add("STORAGE_SECRET_KEY");
        return falta;
    }

    private static boolean vazio(String v) {
        return v == null || v.trim().isEmpty();
    }

    /**
     * Lança {@link IllegalStateException} com a mesma mensagem que o Node
     * quando a configuração não serve para produção. Primeiro o JWT_SECRET,
     * depois o armazenamento — a mesma ordem que env.js.
     */
    static void verificar(String jwtSecret, String storageProvider, String bucket, String accessKey, String secretKey) {
        String segredo = jwtSecret == null ? "" : jwtSecret;
        List<String> motivos = new ArrayList<>();
        if (segredo.isEmpty() || "CHANGE_ME".equals(segredo)) {
            motivos.add("JWT_SECRET");
        } else {
            String fraco = jwtSecretFraco(segredo);
            if (fraco != null) motivos.add("JWT_SECRET (" + fraco + ")");
        }
        if (!motivos.isEmpty()) {
            throw new IllegalStateException("Configuração em falta ou insegura para produção: " + String.join(", ", motivos)
                    + ". Verifique as variáveis de ambiente do serviço.");
        }

        String provider = storageProvider == null ? "local" : storageProvider.trim();
        if (provider.isEmpty()) provider = "local";
        List<String> emFalta = storageEmFalta(provider, bucket, accessKey, secretKey);
        if (!"s3".equals(provider) || !emFalta.isEmpty()) {
            String motivo = !"s3".equals(provider)
                    ? "STORAGE_PROVIDER não está definido como \"s3\""
                    : "STORAGE_PROVIDER=s3 mas faltam credenciais: " + String.join(", ", emFalta);
            throw new IllegalStateException("Armazenamento inseguro para produção — " + motivo
                    + ". Configure o Supabase Storage (ou outro S3-compatível) e defina STORAGE_PROVIDER=s3, STORAGE_BUCKET, "
                    + "STORAGE_ACCESS_KEY, STORAGE_SECRET_KEY. Verifique em Admin do Sistema → Configurações e Suporte → "
                    + "Prontidão para produção.");
        }
    }

    /**
     * Espelha, em produção, a mesma verificação que {@link ao.kixima.security.CorsOrigins}
     * e {@link ao.kixima.security.SessionCookieUtil} já fazem sozinhas — repetida
     * aqui para o arranque falhar de forma explícita, em vez de a má
     * configuração só aparecer num pedido real.
     */
    static void verificarCorsECookie(boolean permiteQualquerOrigem, boolean semOrigemConfigurada, boolean cookieSeguro) {
        List<String> motivos = new ArrayList<>();
        if (permiteQualquerOrigem) {
            motivos.add("CORS aceitaria qualquer origem — confirme que nenhum perfil \"dev\"/\"test\" está activo ao lado de \"prod\"");
        }
        if (semOrigemConfigurada) {
            motivos.add("nenhuma origem web autorizada — defina APP_URL ou CORS_ORIGINS");
        }
        if (!cookieSeguro) {
            motivos.add("o cookie de sessão não sairia com Secure");
        }
        if (!motivos.isEmpty()) {
            throw new IllegalStateException("Configuração de CORS/cookie insegura ou incompleta para produção: "
                    + String.join("; ", motivos) + ".");
        }
    }
}
