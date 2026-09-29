package ao.kixima.feedback;

import ao.kixima.feedback.dto.CreateFeedbackRequest;
import ao.kixima.feedback.dto.FeedbackCreatedDto;
import ao.kixima.feedback.dto.FeedbackDto;
import ao.kixima.feedback.dto.FeedbackOptionsResponse;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.springframework.http.HttpStatus.CREATED;

/**
 * Espelha backend/src/routes/feedbackRoutes.js — submissão pelo utilizador
 * autenticado (nunca anónima) e consulta das próprias avaliações.
 *
 * NÃO PORTADO: {@code feedbackLimiter} (10 envios/15min) — precisa de
 * Bucket4j, mesma lacuna documentada noutros limitadores (Supplier
 * Development, Chat/Suporte).
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @GetMapping("/opcoes")
    public FeedbackOptionsResponse opcoes() {
        CurrentUser user = CurrentUserHolder.get();
        return feedbackService.opcoes(user.companyId(), user.id());
    }

    @GetMapping("/minhas")
    public List<FeedbackDto> minhas() {
        return feedbackService.minhas(CurrentUserHolder.get().id());
    }

    @PostMapping
    @ResponseStatus(CREATED)
    public FeedbackCreatedDto criar(@RequestBody CreateFeedbackRequest body) {
        CurrentUser user = CurrentUserHolder.get();
        return feedbackService.criar(user.id(), user.companyId(), body.categoria(), body.targetId(), body.rating(), body.message());
    }
}
