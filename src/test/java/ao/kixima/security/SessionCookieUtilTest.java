package ao.kixima.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O cookie de sessão (`kixima_sessao`) tem de sair com {@code Secure} em
 * qualquer ambiente que não seja explicitamente "dev"/"test" — nunca por
 * omissão de {@code SPRING_PROFILES_ACTIVE}. Ver EnvCorsProducaoTest para a
 * verificação equivalente no guardião de arranque de produção.
 */
class SessionCookieUtilTest {

    private static boolean secureCom(String... perfis) {
        MockEnvironment env = new MockEnvironment();
        if (perfis.length > 0) env.setActiveProfiles(perfis);
        JwtService jwt = new JwtService("teste-jwt-secret-com-pelo-menos-32-caracteres-000000", "1d");
        return new SessionCookieUtil(jwt, env).isSecure();
    }

    @Test
    void semNenhumPerfilActivoOCookieContinuaSecure() {
        // Antes da correcção: SPRING_PROFILES_ACTIVE vazio não continha "prod" -> Secure=false.
        assertThat(secureCom()).isTrue();
    }

    @Test
    void comPerfilProdOCookieESecure() {
        assertThat(secureCom("prod")).isTrue();
    }

    @Test
    void comPerfilDevOuTestExplicitoOCookieNaoESecure() {
        assertThat(secureCom("dev")).isFalse();
        assertThat(secureCom("test")).isFalse();
    }

    @Test
    void perfilProdEDevAoMesmoTempoDeixaDeSerSecure() {
        // Caso residual que ProducaoStartupGuard apanha à parte: os dois activos ao mesmo tempo.
        assertThat(secureCom("prod", "dev")).isFalse();
    }
}
