package ao.kixima.admin;

import ao.kixima.audit.AuditService;
import ao.kixima.audit.dto.AuditLogListResponse;
import ao.kixima.security.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static ao.kixima.security.PersonaRole.ADMIN_SISTEMA;

/**
 * Espelha o troço de backend/src/routes/adminRoutes.js exercitado neste
 * marco (M5): {@code GET /audit-logs} — trilho de auditoria financeira,
 * append-only, consulta paginada/filtrável. `router.use(requireRole(
 * 'ADMIN_SISTEMA'))` no Node aplica-se a TODAS as rotas de adminRoutes.js
 * sem `requirePermission` extra nesta em particular — qualquer Admin do
 * Sistema (mesmo com `adminAreas` restrito) vê o trilho de auditoria.
 * As restantes rotas de adminRoutes.js (convites de assessor, taxas da
 * plataforma, prontidão, cópias de segurança, feedback) ficam para o
 * resto do M5/M6, à medida que os respectivos domínios forem portados.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminAuditController {

    private final AuditService auditService;

    public AdminAuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/audit-logs")
    @RequireRole({ADMIN_SISTEMA})
    public AuditLogListResponse auditLogs(@RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer limit,
                                           @RequestParam(required = false) String action,
                                           @RequestParam(required = false) String q) {
        return auditService.list(page, limit, action, q);
    }
}
