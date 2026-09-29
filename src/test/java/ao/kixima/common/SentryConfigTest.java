package ao.kixima.common;

import ao.kixima.common.error.SentryReporter;
import io.sentry.spring.jakarta.SentryExceptionResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Espelha o contrato de config/sentry.js: sem DSN (e sempre nos testes) o
 * Sentry está desligado e {@code captureException} é no-op; e quem decide o
 * que se reporta é o GlobalExceptionHandler, não o resolver do starter.
 */
@SpringBootTest
@ActiveProfiles("test")
class SentryConfigTest {

    @Autowired
    private SentryReporter sentry;

    @Autowired
    private SentryExceptionResolver resolver;

    @Test
    void semDsnOSentryEstaDesligadoECapturarENoOp() {
        assertThat(sentry.enabled()).isFalse();
        sentry.captureException(new IllegalStateException("não deve rebentar"));
        sentry.captureException(new IllegalStateException("nem com pedido"), null);
    }

    @Test
    void oResolverDoStarterNaoCapturaNada() {
        // A subclasse de SentryConfig substitui o resolver do starter (que capturaria 4xx e em duplicado).
        assertThat(resolver.getClass().getName()).startsWith("ao.kixima.config.SentryConfig");
        assertThat(resolver.resolveException(null, null, null, new RuntimeException("x"))).isNull();
    }
}
