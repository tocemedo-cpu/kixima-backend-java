package ao.kixima.common.error;

import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import io.sentry.Sentry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Espelha backend/src/config/sentry.js — rastreio de erros que só existe
 * quando SENTRY_DSN está definido; sem ele, tudo aqui é no-op e a aplicação
 * corre normalmente (o mesmo código serve dev e produção, sem ramificações).
 * O {@code Sentry.init} em si é feito pelo starter do Spring Boot a partir do
 * bloco {@code sentry:} em application.yml.
 */
@Component
public class SentryReporter {

    private static final Logger log = LoggerFactory.getLogger(SentryReporter.class);

    @EventListener(ApplicationReadyEvent.class)
    public void anunciar() {
        if (Sentry.isEnabled()) log.info("Sentry: rastreio de erros ativo.");
    }

    public boolean enabled() {
        return Sentry.isEnabled();
    }

    /**
     * Captura uma exceção com contexto do pedido (método, caminho, id do
     * utilizador — os mesmos `extra` do Node). No-op se o Sentry não estiver
     * configurado. Só para erros inesperados (5xx), não para erros de
     * validação/regra de negócio (4xx), que são o funcionamento normal.
     */
    public void captureException(Throwable err, HttpServletRequest req) {
        if (!Sentry.isEnabled()) return;
        if (req == null) {
            Sentry.captureException(err);
            return;
        }
        String caminho = req.getQueryString() == null ? req.getRequestURI() : req.getRequestURI() + "?" + req.getQueryString();
        CurrentUser user = CurrentUserHolder.get();
        Sentry.captureException(err, scope -> {
            scope.setExtra("method", req.getMethod());
            scope.setExtra("path", caminho);
            if (user != null && user.id() != null) scope.setExtra("userId", user.id()); // `req.user?.id` — omitido sem sessão
        });
    }

    public void captureException(Throwable err) {
        captureException(err, null);
    }
}
