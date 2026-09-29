package ao.kixima.cobranca;

import ao.kixima.audit.AuditService;
import ao.kixima.cobranca.CobrancaDtos.AddonCobrancaDto;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.PersonaRole;
import ao.kixima.security.RequirePermission;
import ao.kixima.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

import static ao.kixima.security.AdminArea.FINANCEIRO;
import static ao.kixima.security.PersonaRole.ADMIN_SISTEMA;
import static ao.kixima.security.PersonaRole.COMPANY_ADMIN;
import static ao.kixima.security.PersonaRole.COMPRADOR;
import static org.springframework.http.HttpStatus.CREATED;

/** Espelha addonRoutes.js — add-ons pagos (hoje só o Automatic PO Robot), mesma separação e guards de assinaturaRoutes.js. */
@RestController
@RequestMapping("/api/addons")
public class AddonController {

    private final AddonCobrancaService svc;
    private final AuditService auditService;

    public AddonController(AddonCobrancaService svc, AuditService auditService) {
        this.svc = svc;
        this.auditService = auditService;
    }

    /** Catálogo de add-ons disponíveis — qualquer utilizador autenticado. */
    @GetMapping("/catalogo")
    public List<Map<String, Object>> catalogo() {
        return svc.catalogo();
    }

    // --- Lado KIXIMA -------------------------------------------------------------

    @GetMapping("/fila")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(FINANCEIRO)
    public Map<String, Object> fila() {
        return svc.fila();
    }

    @PostMapping("/{id}/confirmar")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(FINANCEIRO)
    public AddonCobrancaDto confirmar(@PathVariable String id, @RequestBody(required = false) AssinaturaController.NotasRequest body, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        return svc.confirmar(id, user.id(), body == null ? null : body.notas(), auditService.actorFrom(user, req));
    }

    // --- Lado da empresa ---------------------------------------------------------

    @GetMapping("/{addonKey}/estado")
    @RequireRole({COMPANY_ADMIN, PersonaRole.FINANCEIRO, COMPRADOR})
    public Map<String, Object> estado(@PathVariable String addonKey) {
        return svc.estado(CurrentUserHolder.get().companyId(), addonKey);
    }

    @PostMapping("/{addonKey}/pedir")
    @ResponseStatus(CREATED)
    @RequireRole({COMPANY_ADMIN})
    public AddonCobrancaDto pedir(@PathVariable String addonKey, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        return svc.pedir(user.companyId(), addonKey, user.id(), auditService.actorFrom(user, req));
    }

    @PostMapping("/{id}/comprovativo")
    @RequireRole({COMPANY_ADMIN, PersonaRole.FINANCEIRO})
    public AddonCobrancaDto comprovativo(@PathVariable String id, @RequestParam(value = "comprovativo", required = false) MultipartFile comprovativo,
                                         HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        return svc.submeterComprovativo(user.companyId(), id, comprovativo, auditService.actorFrom(user, req));
    }

    @PostMapping("/{id}/cancelar")
    @RequireRole({COMPANY_ADMIN, ADMIN_SISTEMA})
    @RequirePermission(FINANCEIRO)
    public AddonCobrancaDto cancelar(@PathVariable String id, @RequestBody(required = false) AssinaturaController.MotivoRequest body, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        String escopo = user.role() == PersonaRole.ADMIN_SISTEMA ? null : user.companyId();
        return svc.cancelar(id, body == null ? null : body.motivo(), escopo, auditService.actorFrom(user, req));
    }
}
