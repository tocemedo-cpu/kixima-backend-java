package ao.kixima.kit;

import ao.kixima.audit.AuditService;
import ao.kixima.common.error.ValidationException;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static ao.kixima.security.PersonaRole.COMPANY_ADMIN;
import static ao.kixima.security.PersonaRole.FORNECEDOR;

/** Espelha backend/src/routes/kitRoutes.js. */
@RestController
@RequestMapping("/api/kits")
public class KitController {

    private static final Pattern UUID_RE = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    public record Item(String productId, Integer quantity) {
    }

    public record CreateKitRequest(String name, String description, List<Item> items) {
    }

    private final KitService kitService;
    private final AuditService auditService;

    public KitController(KitService kitService, AuditService auditService) {
        this.kitService = kitService;
        this.auditService = auditService;
    }

    @GetMapping
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public List<Map<String, Object>> list() {
        return kitService.listKits(CurrentUserHolder.get().companyId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public Map<String, Object> create(@RequestBody(required = false) CreateKitRequest body, HttpServletRequest req) {
        // createKitSchema: name ≥ 2, items ≥ 1 com productId uuid e quantity inteiro positivo (default 1).
        if (body == null || body.name() == null || body.name().trim().length() < 2) throw new ValidationException("Indique o nome do kit.");
        if (body.items() == null || body.items().isEmpty()) throw new ValidationException("Um kit precisa de pelo menos um produto.");
        List<KitService.ItemPedido> items = new ArrayList<>();
        for (Item i : body.items()) {
            if (i == null || i.productId() == null || !UUID_RE.matcher(i.productId()).matches()) throw new ValidationException("productId inválido.");
            int q = i.quantity() == null ? 1 : i.quantity();
            if (q <= 0) throw new ValidationException("A quantidade tem de ser um inteiro positivo.");
            items.add(new KitService.ItemPedido(i.productId(), q));
        }
        CurrentUser user = CurrentUserHolder.get();
        Map<String, Object> kit = kitService.createKit(user.companyId(), body.name(), body.description(), items);
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(user, req), "CATALOGO_KIT_CRIADO", "Kit",
                (String) kit.get("id"), (String) kit.get("name"), null));
        return kit;
    }

    @DeleteMapping("/{id}")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public Map<String, Object> delete(@PathVariable String id, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        Map<String, Object> r = kitService.deleteKit(id, user.companyId());
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(user, req), "CATALOGO_KIT_REMOVIDO", "Kit", id, null, null));
        return r;
    }
}
