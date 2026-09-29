package ao.kixima.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Espelha tests/env-storage-s3-producao.test.js. Em modo 'local' os ficheiros
 * carregados desaparecem a cada deploy; uma má configuração de storage em
 * produção falha o arranque, tal como já acontece para o JWT_SECRET. Cada
 * caso levanta um contexto Spring mínimo com o perfil {@code prod}.
 */
class EnvStorageS3ProducaoTest {

    /** JWT_SECRET válido em todos os casos — este ficheiro testa só o guardião de storage. */
    private static ApplicationContextRunner tentaArrancar(Map<String, String> env) {
        List<String> props = new ArrayList<>(List.of("spring.profiles.active=prod", "kixima.auth.jwt-secret=" + "a".repeat(48)));
        env.forEach((k, v) -> props.add(k + "=" + v));
        return new ApplicationContextRunner()
                .withUserConfiguration(ProducaoStartupGuard.class)
                .withPropertyValues(props.toArray(String[]::new));
    }

    @Test
    void recusaArrancarEmModoLocalStorageProviderNuncaDefinido() {
        tentaArrancar(Map.of("kixima.storage.provider", "")).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("Armazenamento inseguro");
        });
    }

    @Test
    void recusaArrancarComStorageProviderS3MasCredenciaisIncompletas() {
        tentaArrancar(Map.of("kixima.storage.provider", "s3", "kixima.storage.bucket", "kixima",
                "kixima.storage.access-key", "", "kixima.storage.secret-key", "")).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("STORAGE_ACCESS_KEY");
        });
    }

    @Test
    void aceitaComStorageProviderS3ECredenciaisCompletas() {
        tentaArrancar(Map.of("kixima.storage.provider", "s3", "kixima.storage.bucket", "kixima",
                "kixima.storage.access-key", "ak", "kixima.storage.secret-key", "sk")).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(ProducaoStartupGuard.class);
        });
    }
}
