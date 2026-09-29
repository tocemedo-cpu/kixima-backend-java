package ao.kixima.admin;

import ao.kixima.audit.AuditService;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.MfaReminderService;
import ao.kixima.security.RequirePermission;
import ao.kixima.security.RequireRole;
import ao.kixima.security.dto.EnviarLembretesResultDto;
import ao.kixima.security.dto.MfaPendingUserDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static ao.kixima.security.AdminArea.OPERACOES;
import static ao.kixima.security.PersonaRole.ADMIN_SISTEMA;

/**
 * Espelha o troço de contas com poder sem 2FA de backend/src/routes/adminRoutes.js
 * ({@code GET /mfa-pendentes}, {@code POST /mfa-lembrete}) — não se pode ativar
 * a 2FA por outra pessoa, só pedir-lhe que o faça.
 */
@RestController
@RequestMapping("/api/admin")
public class MfaReminderController {

    private final MfaReminderService mfaReminderService;
    private final AuditService auditService;

    public MfaReminderController(MfaReminderService mfaReminderService, AuditService auditService) {
        this.mfaReminderService = mfaReminderService;
        this.auditService = auditService;
    }

    public record EnviarLembreteRequest(List<String> userIds) {
    }

    @GetMapping("/mfa-pendentes")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(OPERACOES)
    public List<MfaPendingUserDto> pendentes() {
        return mfaReminderService.pendentes();
    }

    @PostMapping("/mfa-lembrete")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(OPERACOES)
    public EnviarLembretesResultDto enviarLembrete(@RequestBody(required = false) EnviarLembreteRequest body,
                                                     HttpServletRequest req) {
        List<String> userIds = body == null ? null : body.userIds();
        var actor = auditService.actorFrom(CurrentUserHolder.get(), req);
        return mfaReminderService.enviarLembretes(userIds, actor);
    }
}
