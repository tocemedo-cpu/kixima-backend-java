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

/** Espelha financeiroRoutes.js — telas do Financeiro (leitura; o pagamento continua em /api/payments). */
@RestController
@RequestMapping("/api/financeiro")
public class FinanceiroController {
    private final FinanceiroPanelService svc;

    public FinanceiroController(FinanceiroPanelService svc) {
        this.svc = svc;
    }

    @RequireRole({FINANCEIRO, COMPANY_ADMIN})
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return svc.overview(CurrentUserHolder.get().companyId());
    }

    @RequireRole({FINANCEIRO, COMPANY_ADMIN})
    @GetMapping("/invoices")
    public Map<String, Object> invoices() {
        return svc.invoices(CurrentUserHolder.get().companyId());
    }

    @RequireRole({FINANCEIRO, COMPANY_ADMIN})
    @GetMapping("/payments")
    public Map<String, Object> payments(@RequestParam(required = false) String status) {
        return svc.payments(CurrentUserHolder.get().companyId(), status);
    }
}
