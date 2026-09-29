package ao.kixima.config;

import io.sentry.IScopes;
import io.sentry.spring.jakarta.SentryExceptionResolver;
import io.sentry.spring.jakarta.tracing.TransactionNameProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.ModelAndView;

/**
 * Afinação do starter do Sentry para reproduzir config/sentry.js: no Node só
 * o errorHandler decide o que vai para o Sentry (5xx e erros inesperados —
 * nunca os 4xx, que são o funcionamento normal). O starter traz um
 * {@code HandlerExceptionResolver} que capturaria TODAS as exceções antes do
 * {@link ao.kixima.common.error.GlobalExceptionHandler} as ver, 4xx incluídos
 * e em duplicado; substitui-se por um que não captura nada, e a decisão fica
 * onde está no Node.
 */
@Configuration
public class SentryConfig {

    @Bean
    @ConditionalOnProperty(name = "sentry.dsn")
    public SentryExceptionResolver sentryExceptionResolver(IScopes scopes, TransactionNameProvider transactionNameProvider) {
        return new SentryExceptionResolver(scopes, transactionNameProvider, Ordered.HIGHEST_PRECEDENCE) {
            @Override
            public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                                 Object handler, Exception ex) {
                return null; // quem captura é o GlobalExceptionHandler, como o errorHandler.js
            }
        };
    }
}
