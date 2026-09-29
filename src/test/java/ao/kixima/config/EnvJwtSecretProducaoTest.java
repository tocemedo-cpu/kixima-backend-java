package ao.kixima.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Espelha tests/env-jwt-secret-producao.test.js. No Node cada caso corre num
 * subprocesso (env.js lança ao ser importado); aqui cada caso levanta um
 * contexto Spring mínimo com o perfil {@code prod} e só o guardião de
 * arranque — o arranque "falha" quando o contexto não sobe.
 */
class EnvJwtSecretProducaoTest {

    /** As variáveis fixas de cada caso — storage válido, para isolar o que este ficheiro testa (JWT_SECRET). */
    private static ApplicationContextRunner tentaArrancar(Map<String, String> env) {
        List<String> props = new ArrayList<>(List.of(
                "spring.profiles.active=prod",
                "kixima.storage.provider=s3", "kixima.storage.bucket=kixima",
                "kixima.storage.access-key=ak", "kixima.storage.secret-key=sk"));
        env.forEach((k, v) -> props.add(k + "=" + v));
        return new ApplicationContextRunner()
                .withUserConfiguration(ProducaoStartupGuard.class)
                .withPropertyValues(props.toArray(String[]::new));
    }

    @Test
    void recusaOValorDeExemploDoEnvExample() {
        tentaArrancar(Map.of("kixima.auth.jwt-secret", "troque-este-valor")).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("valor de exemplo");
        });
    }

    @Test
    void recusaChangeMeComportamentoJaExistenteContinuaAValer() {
        tentaArrancar(Map.of("kixima.auth.jwt-secret", "CHANGE_ME")).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("JWT_SECRET");
        });
    }

    @Test
    void recusaUmSegredoCurtoMesmoQueNaoSejaUmPlaceholderConhecido() {
        tentaArrancar(Map.of("kixima.auth.jwt-secret", "segredo-curto-demais")).run(ctx -> { // 21 caracteres
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("caracteres");
        });
    }

    @Test
    void aceitaUmSegredoRealLongoEAleatorio() {
        tentaArrancar(Map.of("kixima.auth.jwt-secret", "a".repeat(48))).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(ProducaoStartupGuard.class);
        });
    }

    @Test
    void foraDeProducaoOGuardiaoNaoExiste() {
        // Em desenvolvimento/teste os valores por omissão de application.yml continuam a servir.
        new ApplicationContextRunner()
                .withUserConfiguration(ProducaoStartupGuard.class)
                .withPropertyValues("spring.profiles.active=test", "kixima.auth.jwt-secret=troque-este-valor")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(ProducaoStartupGuard.class);
                });
    }
}
