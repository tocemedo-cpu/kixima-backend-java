package ao.kixima.feedback;

import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditService;
import ao.kixima.common.pagination.PaginaResposta;
import ao.kixima.feedback.dto.FeedbackDto;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.RequirePermission;
import ao.kixima.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static ao.kixima.security.AdminArea.SUPORTE;
import static ao.kixima.security.PersonaRole.ADMIN_SISTEMA;
import static org.springframework.http.HttpStatus.NO_CONTENT;

/**
 * Espelha o troço "Moderação das avaliações públicas" de
 * backend/src/routes/adminRoutes.js — ninguém vê uma avaliação na home sem
 * passar por aqui primeiro ({@code approved: false} ao ser criada, sempre).
 */
@RestController
@RequestMapping("/api/admin")
public class FeedbackAdminController {

    private final FeedbackService feedbackService;
    private final AuditService auditService;

    public FeedbackAdminController(FeedbackService feedbackService, AuditService auditService) {
        this.feedbackService = feedbackService;
        this.auditService = auditService;
    }

    @GetMapping("/feedback")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(SUPORTE)
    public PaginaResposta<FeedbackDto> listar(@RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer limit,
                                               @RequestParam(required = false) String status) {
        return feedbackService.listarAdmin(page, limit, status);
    }

    @PatchMapping("/feedback/{id}/aprovar")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(SUPORTE)
    public FeedbackDto aprovar(@PathVariable String id, HttpServletRequest req) {
        FeedbackDto aprovada = feedbackService.aprovar(id);
        Actor actor = auditService.actorFrom(CurrentUserHolder.get(), req);
        auditService.recordSafe(new AuditService.Entry(actor, "FEEDBACK_APROVADO", "Feedback", aprovada.id(),
                aprovada.company() == null ? null : aprovada.company().name(),
                Map.of("autor", String.valueOf(aprovada.user() == null ? null : aprovada.user().name()),
                        "categoria", aprovada.categoria(), "classificacao", aprovada.rating())));
        return aprovada;
    }

    @DeleteMapping("/feedback/{id}")
    @ResponseStatus(NO_CONTENT)
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(SUPORTE)
    public void remover(@PathVariable String id, HttpServletRequest req) {
        feedbackService.remover(id);
        Actor actor = auditService.actorFrom(CurrentUserHolder.get(), req);
        auditService.recordSafe(new AuditService.Entry(actor, "FEEDBACK_REMOVIDO", "Feedback", id, null, null));
    }
}
