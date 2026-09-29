package ao.kixima.feedback;

import ao.kixima.feedback.dto.PublicFeedbackResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Espelha o troço de {@code /feedback} de backend/src/routes/publicRoutes.js
 * — a parede pública de avaliações da homepage, só leitura, só o que já foi
 * aprovado. NÃO PORTADO: {@code GET /api/public/stats}
 * (publicStatsService.resumo — fora do âmbito deste item do plano).
 */
@RestController
@RequestMapping("/api/public")
public class FeedbackPublicController {

    private final FeedbackService feedbackService;

    public FeedbackPublicController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @GetMapping("/feedback")
    public PublicFeedbackResponse publicar() {
        return feedbackService.publicar();
    }
}
