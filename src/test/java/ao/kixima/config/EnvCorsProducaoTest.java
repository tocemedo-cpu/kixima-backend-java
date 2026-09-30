package ao.kixima.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O achado da auditoria de segurança: CorsOrigins/SessionCookieUtil ficavam
 * "qualquer origem"/"sem Secure" sempre que SPRING_PROFILES_ACTIVE não
 * continha "prod" — incluindo por omissão, um perfil escrito à pressa no
 * deploy, ou "prod,dev" em simultâneo. Este ficheiro prova que o guardião de
 * arranque apanha isso em produção, e que uma configuração correcta continua
 * a arrancar sem incidente.
 */
class EnvCorsProducaoTest {

    /** JWT_SECRET/storage válidos em todos os casos — este ficheiro testa só CORS/cookie. */
    private static ApplicationContextRunner tentaArrancar(Map<String, String> env) {
        List<String> props = new ArrayList<>(List.of(
                "spring.profiles.active=prod",
                "kixima.auth.jwt-secret=" + "a".repeat(48),
                "kixima.storage.provider=s3", "kixima.storage.bucket=kixima",
                "kixima.storage.access-key=ak", "kixima.storage.secret-key=sk"));
        env.forEach((k, v) -> props.add(k + "=" + v));
        return new ApplicationContextRunner()
                .withUserConfiguration(ProducaoStartupGuard.class)
                .withPropertyValues(props.toArray(String[]::new));
    }

    @Test
    void recusaArrancarSemNenhumaOrigemWebConfigurada() {
        tentaArrancar(Map.of()).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("nenhuma origem web autorizada");
        });
    }

    @Test
    void recusaArrancarComPerfilDevActivoAoLadoDeProd() {
        // SPRING_PROFILES_ACTIVE=prod,dev — os dois são válidos ao mesmo tempo no Spring;
        // isto reabriria CORS e desligaria o Secure do cookie, mesmo com "prod" presente.
        tentaArrancar(Map.of("spring.profiles.active", "prod,dev", "kixima.app-url", "https://kixima.example.com")).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("CORS aceitaria qualquer origem");
        });
    }

    @Test
    void recusaArrancarComPerfilTestActivoAoLadoDeProd() {
        tentaArrancar(Map.of("spring.profiles.active", "prod,test", "kixima.app-url", "https://kixima.example.com")).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("o cookie de sessão não sairia com Secure");
        });
    }

    @Test
    void aceitaComAppUrlConfigurado() {
        tentaArrancar(Map.of("kixima.app-url", "https://app.kixima.co.ao")).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(ProducaoStartupGuard.class);
        });
    }

    @Test
    void aceitaComCorsOriginsConfiguradoMesmoSemAppUrl() {
        tentaArrancar(Map.of("kixima.cors.origins", "https://parceiro.example.com")).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(ProducaoStartupGuard.class);
        });
    }

    @Test
    void verificarCorsECookieIsoladoDosTresMotivosNaMesmaFalha() {
        assertThatMotivos(true, true, false);
    }

    private static void assertThatMotivos(boolean permiteQualquerOrigem, boolean semOrigemConfigurada, boolean cookieSeguro) {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> ProducaoStartupGuard.verificarCorsECookie(permiteQualquerOrigem, semOrigemConfigurada, cookieSeguro))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORS aceitaria qualquer origem")
                .hasMessageContaining("nenhuma origem web autorizada")
                .hasMessageContaining("Secure");
    }
}
