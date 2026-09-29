package ao.kixima.cobranca;

import ao.kixima.audit.AuditService;
import ao.kixima.cobranca.CobrancaDtos.PlanoCobrancaDto;
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

import java.util.Map;

import static ao.kixima.security.AdminArea.FINANCEIRO;
import static ao.kixima.security.PersonaRole.ADMIN_SISTEMA;
import static ao.kixima.security.PersonaRole.COMPANY_ADMIN;
import static org.springframework.http.HttpStatus.CREATED;

/**
 * Espelha assinaturaRoutes.js — os dois lados da mesma cobrança (a empresa
 * pede e paga; a KIXIMA confirma), cada um com o seu guard explícito.
 */
@RestController
@RequestMapping("/api/assinatura")
public class AssinaturaController {

    private final AssinaturaService svc;
    private final CanaisPagamentoService canaisPagamentoService;
    private final AuditService auditService;

    public AssinaturaController(AssinaturaService svc, CanaisPagamentoService canaisPagamentoService, AuditService auditService) {
        this.svc = svc;
        this.canaisPagamentoService = canaisPagamentoService;
        this.auditService = auditService;
    }

    public record PedirRequest(String plano, Boolean aceitaPerdas) {
    }

    public record NotasRequest(String notas) {
    }

    public record PagarComRequest(String canal, String telemovel) {
    }

    public record MotivoRequest(String motivo) {
    }

    // --- Lado KIXIMA ------------------------------------------------------------

    @GetMapping("/fila")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(FINANCEIRO)
    public Map<String, Object> fila() {
        return svc.fila();
    }

    @PostMapping("/{id}/confirmar")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(FINANCEIRO)
    public PlanoCobrancaDto confirmar(@PathVariable String id, @RequestBody(required = false) NotasRequest body, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        return svc.confirmar(id, user.id(), body == null ? null : body.notas(), auditService.actorFrom(user, req));
    }

    // --- Lado da empresa --------------------------------------------------------
    // Só o Company Admin — ver, pedir, pagar e carregar o comprovativo são
    // todos dele. O Financeiro deixou de ter qualquer acesso a esta rota
    // (chegou a ver o estado e a carregar o comprovativo, por ser quem faz
    // as transferências; deixou de o poder fazer).

    @GetMapping
    @RequireRole({COMPANY_ADMIN})
    public Map<String, Object> estado() {
        return svc.estado(CurrentUserHolder.get().companyId());
    }

    /** O plano é dinheiro e é o Company Admin quem o compromete. */
    @PostMapping("/pedir")
    @ResponseStatus(CREATED)
    @RequireRole({COMPANY_ADMIN})
    public PlanoCobrancaDto pedir(@RequestBody(required = false) PedirRequest body, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        return svc.pedir(user.companyId(), body == null ? null : body.plano(), user.id(),
                body != null && Boolean.TRUE.equals(body.aceitaPerdas()), auditService.actorFrom(user, req));
    }

    /** Quais canais automáticos estão configurados — a página só mostra os que respondem `disponivel: true`. */
    @GetMapping("/canais")
    @RequireRole({COMPANY_ADMIN})
    public Map<String, Object> canais() {
        return canaisPagamentoService.estados();
    }

    @PostMapping("/{id}/pagar-com")
    @RequireRole({COMPANY_ADMIN})
    public PlanoCobrancaDto pagarCom(@PathVariable String id, @RequestBody(required = false) PagarComRequest body, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        return svc.iniciarPagamentoGateway(user.companyId(), id, body == null ? null : body.canal(), body == null ? null : body.telemovel(),
                auditService.actorFrom(user, req));
    }

    /** Comprovativo OBRIGATÓRIO (multipart, campo "comprovativo": PDF ou imagem). */
    @PostMapping("/{id}/comprovativo")
    @RequireRole({COMPANY_ADMIN})
    public PlanoCobrancaDto comprovativo(@PathVariable String id, @RequestParam(value = "comprovativo", required = false) MultipartFile comprovativo,
                                         HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        return svc.submeterComprovativo(user.companyId(), id, comprovativo, auditService.actorFrom(user, req));
    }

    /** Cancelar. O Admin do Sistema cancela qualquer uma; a empresa só as suas. */
    @PostMapping("/{id}/cancelar")
    @RequireRole({COMPANY_ADMIN, ADMIN_SISTEMA})
    @RequirePermission(FINANCEIRO)
    public PlanoCobrancaDto cancelar(@PathVariable String id, @RequestBody(required = false) MotivoRequest body, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        String escopo = user.role() == PersonaRole.ADMIN_SISTEMA ? null : user.companyId();
        return svc.cancelar(id, body == null ? null : body.motivo(), escopo, auditService.actorFrom(user, req));
    }
}
