package ao.kixima.admin;

import ao.kixima.audit.AuditService;
import ao.kixima.payment.PlatformFeeService;
import ao.kixima.payment.dto.PlatformFeeBookDto;
import ao.kixima.payment.dto.PlatformFeeDto;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.RequirePermission;
import ao.kixima.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static ao.kixima.security.AdminArea.FINANCEIRO;
import static ao.kixima.security.PersonaRole.ADMIN_SISTEMA;

/** Espelha o troço "Livro de taxas da plataforma (KIXIMA)" de adminRoutes.js. */
@RestController
@RequestMapping("/api/admin")
public class PlatformFeeAdminController {

    private final PlatformFeeService platformFeeService;
    private final AuditService auditService;

    public PlatformFeeAdminController(PlatformFeeService platformFeeService, AuditService auditService) {
        this.platformFeeService = platformFeeService;
        this.auditService = auditService;
    }

    @GetMapping("/platform-fees")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(FINANCEIRO)
    public PlatformFeeBookDto listar() {
        return platformFeeService.listPlatformFees();
    }

    @PatchMapping("/platform-fees/{id}/charge")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(FINANCEIRO)
    public PlatformFeeDto cobrar(@PathVariable String id, HttpServletRequest req) {
        PlatformFeeDto fee = platformFeeService.chargePlatformFee(id);
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(CurrentUserHolder.get(), req), "TAXA_COBRADA",
                "PlatformFee", fee.id(), fee.id(), Map.of("valor", ao.kixima.common.Decimais.texto(fee.amount()), "moeda", fee.currency())));
        return fee;
    }
}
