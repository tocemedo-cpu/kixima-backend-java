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

/** Espelha companyAdminRoutes.js — telas do Company Admin (só COMPANY_ADMIN, sempre a própria empresa). */
@RestController
@RequestMapping("/api/company-admin")
public class CompanyAdminController {
    private final CompanyAdminPanelService svc;

    public CompanyAdminController(CompanyAdminPanelService svc) {
        this.svc = svc;
    }

    /** Perfil da Empresa ("Organização") — só o Company Admin, sempre a própria empresa. */
    @RequireRole({COMPANY_ADMIN})
    @GetMapping("/organizacao")
    public Map<String, Object> organizacao() {
        return svc.organizacao(CurrentUserHolder.get().companyId());
    }

    @RequireRole({COMPANY_ADMIN})
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return svc.dashboard(CurrentUserHolder.get().companyId());
    }

    @RequireRole({COMPANY_ADMIN})
    @GetMapping("/activities")
    public Map<String, Object> activities(@RequestParam(required = false) String filter) {
        return svc.activities(CurrentUserHolder.get().companyId(), filter);
    }

    @RequireRole({COMPANY_ADMIN})
    @GetMapping("/reports")
    public Map<String, Object> reports() {
        return svc.reports(CurrentUserHolder.get().companyId());
    }

    @RequireRole({COMPANY_ADMIN})
    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        return svc.getSettings(CurrentUserHolder.get().companyId());
    }

    @RequireRole({COMPANY_ADMIN})
    @PutMapping("/settings")
    public Map<String, Object> saveSettings(@RequestBody(required = false) Map<String, Object> body) {
        return svc.saveSettings(CurrentUserHolder.get().companyId(), body);
    }
}
