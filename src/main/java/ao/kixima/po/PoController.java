package ao.kixima.po;

import ao.kixima.audit.AuditService;
import ao.kixima.audit.dto.AuditLogDto;
import ao.kixima.po.dto.CreatePoRequest;
import ao.kixima.po.dto.PurchaseOrderDto;
import ao.kixima.po.dto.PurchaseOrderItemDto;
import ao.kixima.po.dto.ReasonRequest;
import ao.kixima.po.dto.ReceptionRequest;
import ao.kixima.po.dto.ResolveDivergenceRequest;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static ao.kixima.security.PersonaRole.COMPANY_ADMIN;
import static ao.kixima.security.PersonaRole.COMPRADOR;
import static ao.kixima.security.PersonaRole.FORNECEDOR;
import static org.springframework.http.HttpStatus.CREATED;

/**
 * Espelha backend/src/controllers/poController.js + o troço portado de
 * backend/src/routes/poRoutes.js. `history` (linha do tempo via AuditLog)
 * não está incluído — depende de uma leitura filtrada de AuditLog ainda não
 * exposta (M5).
 */
@RestController
@RequestMapping("/api/purchase-orders")
public class PoController {

    private final PoService poService;
    private final PoDtoService poDtoService;
    private final AuditService auditService;

    public PoController(PoService poService, PoDtoService poDtoService, AuditService auditService) {
        this.poService = poService;
        this.poDtoService = poDtoService;
        this.auditService = auditService;
    }

    @PostMapping
    @ResponseStatus(CREATED)
    @RequireRole({COMPRADOR, COMPANY_ADMIN})
    public PurchaseOrderDto create(@Valid @RequestBody CreatePoRequest body, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        List<PoService.ItemPedido> itens = body.items().stream()
                .map(i -> new PoService.ItemPedido(i.productId(), i.quantity())).toList();
        PurchaseOrder po = poService.createPurchaseOrder(user.companyId(), body.supplierCompanyId(), user.id(), itens);
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(user, req), "PO_CRIADA",
                "PurchaseOrder", po.getId(), po.getReference(),
                Map.of("valor", ao.kixima.common.Decimais.texto(po.getTotalAmount()), "moeda", po.getCurrency(), "fornecedor", po.getSupplierCompanyId())));
        return poDtoService.criada(po.getId());
    }

    @GetMapping
    public List<PurchaseOrderDto> list(@RequestParam(required = false) String status) {
        CurrentUser user = CurrentUserHolder.get();
        PoStatus statusEnum = status == null ? null : PoStatus.valueOf(status);
        return poDtoService.listagem(user.companyId(), user.role(), statusEnum);
    }

    @GetMapping("/{id}")
    public PurchaseOrderDto getOne(@PathVariable String id) {
        return poDtoService.detalhe(id, CurrentUserHolder.get());
    }

    /** Linha do tempo auditável da PO (quem fez o quê, quando) — mesmo controlo de acesso de GET /{id}. */
    @GetMapping("/{id}/history")
    public List<AuditLogDto> history(@PathVariable String id) {
        return poService.getPurchaseOrderHistory(id, CurrentUserHolder.get()).stream().map(auditService::toDto).toList();
    }

    @PatchMapping("/{id}/approve")
    @RequireRole({COMPANY_ADMIN})
    public PurchaseOrderDto approve(@PathVariable String id, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        PurchaseOrder po = poService.approvePurchaseOrder(id, user.id(), user.companyId());
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(user, req), "PO_APROVADA",
                "PurchaseOrder", po.getId(), po.getReference(),
                Map.of("valor", ao.kixima.common.Decimais.texto(po.getTotalAmount()), "moeda", po.getCurrency())));
        return toDto(po);
    }

    @PatchMapping("/{id}/reject")
    @RequireRole({COMPANY_ADMIN})
    public PurchaseOrderDto reject(@PathVariable String id, @RequestBody(required = false) ReasonRequest body, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        String reason = body == null ? null : body.reason();
        PurchaseOrder po = poService.rejectPurchaseOrder(id, user.id(), reason, user.companyId());
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(user, req), "PO_REJEITADA",
                "PurchaseOrder", po.getId(), po.getReference(), reasonDetail(reason)));
        return toDto(po);
    }

    @PatchMapping("/{id}/accept")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public PurchaseOrderDto accept(@PathVariable String id, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        PurchaseOrder po = poService.acceptPurchaseOrder(id, user.companyId());
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(user, req), "PO_ACEITE",
                "PurchaseOrder", po.getId(), po.getReference(),
                Map.of("valor", ao.kixima.common.Decimais.texto(po.getTotalAmount()), "moeda", po.getCurrency())));
        return toDto(po);
    }

    @PatchMapping("/{id}/refuse")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public PurchaseOrderDto refuse(@PathVariable String id, @Valid @RequestBody ReasonRequest body, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        PurchaseOrder po = poService.refusePurchaseOrder(id, user.companyId(), body.reason());
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(user, req), "PO_RECUSADA_FORNECEDOR",
                "PurchaseOrder", po.getId(), po.getReference(), Map.of("motivo", body.reason())));
        return toDto(po);
    }

    @PatchMapping("/{id}/dispatch")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public PurchaseOrderDto dispatch(@PathVariable String id, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        PurchaseOrder po = poService.dispatchPurchaseOrder(id, user.companyId());
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(user, req), "PO_DESPACHADA",
                "PurchaseOrder", po.getId(), po.getReference(), null));
        return toDto(po);
    }

    @PatchMapping("/{id}/delivered")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public PurchaseOrderDto delivered(@PathVariable String id, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        PurchaseOrder po = poService.markDelivered(id, user.companyId());
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(user, req), "PO_ENTREGUE",
                "PurchaseOrder", po.getId(), po.getReference(), null));
        return toDto(po);
    }

    @PatchMapping("/{id}/reception")
    @RequireRole({COMPRADOR, COMPANY_ADMIN})
    public PurchaseOrderDto receive(@PathVariable String id, @Valid @RequestBody ReceptionRequest body, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        PurchaseOrder po = poService.confirmReception(id, user.companyId(), new PoService.ConfirmacaoRececao(body.conforme(), body.notes()));
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(user, req), "RECECAO_MERCADORIA",
                "PurchaseOrder", po.getId(), po.getReference(), comNotas(Map.of("conforme", body.conforme()), body.notes())));
        // Receção conforme fecha a ordem automaticamente — evento próprio na linha do tempo, distinto da receção em si.
        if (po.getStatus() == PoStatus.CONCLUIDA) {
            auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(user, req), "PO_CONCLUIDA",
                    "PurchaseOrder", po.getId(), po.getReference(), Map.of("motivo", "receção conforme")));
        }
        return toDto(po);
    }

    @PatchMapping("/{id}/resolve-divergence")
    @RequireRole({COMPRADOR, COMPANY_ADMIN})
    public PurchaseOrderDto resolveDivergence(@PathVariable String id, @Valid @RequestBody ResolveDivergenceRequest body, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        PurchaseOrder po = poService.resolveDivergence(id, user.companyId(), new PoService.ResolucaoDivergencia(body.outcome(), body.notes()));
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(user, req), "DIVERGENCIA_RESOLVIDA",
                "PurchaseOrder", po.getId(), po.getReference(),
                comNotas(Map.of("desfecho", body.outcome()), body.notes())));
        return toDto(po);
    }

    /** `notas: req.body.notes || null` — vazio conta como ausente. */
    private static Map<String, Object> comNotas(Map<String, Object> base, String notes) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(base);
        m.put("notas", notes == null || notes.isEmpty() ? null : notes);
        return m;
    }

    private Map<String, Object> reasonDetail(String reason) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("motivo", reason);
        return m;
    }

    /** Transições devolvem só a linha, como o `prisma.purchaseOrder.update` sem include do Node. */
    private PurchaseOrderDto toDto(PurchaseOrder po) {
        return PurchaseOrderDto.escalar(po);
    }
}
