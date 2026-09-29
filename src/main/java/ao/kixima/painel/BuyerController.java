package ao.kixima.painel;

import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static ao.kixima.security.PersonaRole.COMPANY_ADMIN;
import static ao.kixima.security.PersonaRole.COMPRADOR;
import static ao.kixima.security.PersonaRole.FINANCEIRO;

/** Espelha buyerRoutes.js — telas do Comprador (COMPRADOR ou COMPANY_ADMIN da mesma empresa compradora). */
@RestController
@RequestMapping("/api/buyer")
public class BuyerController {
    private final BuyerPanelService svc;

    public BuyerController(BuyerPanelService svc) {
        this.svc = svc;
    }

    @RequireRole({COMPRADOR, COMPANY_ADMIN})
    @GetMapping("/orders")
    public Map<String, Object> orders(@RequestParam(required = false) String status, @RequestParam(required = false) String q,
                                      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer limit) {
        return svc.orders(CurrentUserHolder.get().companyId(), status, q, Math.max(1, (page == null ? 1 : page)), Math.min(50, Math.max(1, (limit == null ? 10 : limit))));
    }

    @RequireRole({COMPRADOR, COMPANY_ADMIN})
    @GetMapping("/payments")
    public Map<String, Object> payments(@RequestParam(required = false) String status, @RequestParam(required = false) String q) {
        return svc.payments(CurrentUserHolder.get().companyId(), status, q);
    }

    @RequireRole({COMPRADOR, COMPANY_ADMIN})
    @GetMapping("/deliveries")
    public Map<String, Object> deliveries(@RequestParam(required = false) String stage, @RequestParam(required = false) String q) {
        return svc.deliveries(CurrentUserHolder.get().companyId(), stage, q);
    }

    @RequireRole({COMPRADOR, COMPANY_ADMIN})
    @GetMapping("/receptions")
    public Map<String, Object> receptions(@RequestParam(required = false) String status, @RequestParam(required = false) String q) {
        return svc.receptions(CurrentUserHolder.get().companyId(), status, q);
    }

    @RequireRole({COMPRADOR, COMPANY_ADMIN})
    @GetMapping("/suppliers")
    public Map<String, Object> suppliers(@RequestParam(required = false) String status, @RequestParam(required = false) String q) {
        return svc.suppliers(CurrentUserHolder.get().companyId(), status, q);
    }

    @RequireRole({COMPRADOR, COMPANY_ADMIN})
    @GetMapping("/activities")
    public Map<String, Object> activities(@RequestParam(required = false) String filter) {
        return svc.activities(CurrentUserHolder.get().companyId(), filter);
    }

    @RequireRole({COMPRADOR, COMPANY_ADMIN})
    @GetMapping("/profile")
    public Map<String, Object> profile() {
        CurrentUser u = CurrentUserHolder.get();
        return svc.profile(u.id(), u.companyId());
    }
}
