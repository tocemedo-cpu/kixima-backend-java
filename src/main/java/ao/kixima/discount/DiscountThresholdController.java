package ao.kixima.discount;

import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditService;
import ao.kixima.discount.dto.CreateDiscountThresholdRequest;
import ao.kixima.discount.dto.DiscountThresholdDto;
import ao.kixima.discount.dto.UpdateDiscountThresholdRequest;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.RequirePermission;
import ao.kixima.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static ao.kixima.security.AdminArea.FINANCEIRO;
import static ao.kixima.security.PersonaRole.ADMIN_SISTEMA;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.NO_CONTENT;

/**
 * Espelha o troço "Lado KIXIMA" de categoryManagementRoutes.js — gestão dos
 * patamares de desconto por economia de escala pelo Admin do Sistema
 * (área Financeiro). NÃO PORTADO: o troço "Lado da empresa" ({@code /analise},
 * {@code /produtos/:id/media-mensal}) — ver DiscountThresholdService, javadoc.
 */
@RestController
@RequestMapping("/api/category-management/admin/thresholds")
public class DiscountThresholdController {

    private final DiscountThresholdService discountThresholdService;
    private final AuditService auditService;

    public DiscountThresholdController(DiscountThresholdService discountThresholdService, AuditService auditService) {
        this.discountThresholdService = discountThresholdService;
        this.auditService = auditService;
    }

    @GetMapping
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(FINANCEIRO)
    public List<DiscountThresholdDto> listar() {
        return discountThresholdService.listar(false);
    }

    @PostMapping
    @ResponseStatus(CREATED)
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(FINANCEIRO)
    public DiscountThresholdDto criar(@RequestBody CreateDiscountThresholdRequest body, HttpServletRequest req) {
        return discountThresholdService.criar(body, actor(req));
    }

    @PutMapping("/{id}")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(FINANCEIRO)
    public DiscountThresholdDto atualizar(@PathVariable String id, @RequestBody UpdateDiscountThresholdRequest body, HttpServletRequest req) {
        return discountThresholdService.atualizar(id, body, actor(req));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(NO_CONTENT)
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(FINANCEIRO)
    public void remover(@PathVariable String id, HttpServletRequest req) {
        discountThresholdService.remover(id, actor(req));
    }

    private Actor actor(HttpServletRequest req) {
        return auditService.actorFrom(CurrentUserHolder.get(), req);
    }
}
