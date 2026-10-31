package ao.kixima.company;

import ao.kixima.audit.AuditService;
import ao.kixima.company.dto.OnboardingDto;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

import static ao.kixima.security.PersonaRole.COMPANY_ADMIN;

/**
 * Cadastro empresarial em etapas — o administrador da empresa vê, guarda e submete o cadastro da SUA empresa. É um dos
 * poucos caminhos abertos a uma empresa ainda por aprovar (ver {@link ao.kixima.security.CadastroGate}).
 */
@RestController
@RequestMapping("/api/onboarding")
public class OnboardingController {

    private final OnboardingService onboardingService;
    private final AuditService auditService;

    public OnboardingController(OnboardingService onboardingService, AuditService auditService) {
        this.onboardingService = onboardingService;
        this.auditService = auditService;
    }

    @GetMapping
    @RequireRole({COMPANY_ADMIN})
    public OnboardingDto obter() {
        return onboardingService.obter(CurrentUserHolder.get().companyId());
    }

    /** Guarda o que vier (parcial): «guardar e continuar mais tarde». Não valida o conjunto. */
    @PutMapping
    @RequireRole({COMPANY_ADMIN})
    public OnboardingDto guardar(@RequestBody OnboardingService.Alteracoes body) {
        return onboardingService.guardar(CurrentUserHolder.get().companyId(), body);
    }

    @PostMapping(value = "/documents/{tipo}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireRole({COMPANY_ADMIN})
    public OnboardingDto enviarDocumento(@PathVariable String tipo, @RequestParam("file") MultipartFile file) {
        return onboardingService.enviarDocumento(CurrentUserHolder.get().companyId(), tipo, file);
    }

    @PostMapping("/submit")
    @RequireRole({COMPANY_ADMIN})
    public OnboardingDto submeter(HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        OnboardingDto resultado = onboardingService.submeter(user.companyId());
        Map<String, Object> detail = new LinkedHashMap<>(auditService.contextoFrom(req));
        detail.put("tipo", resultado.type());
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(user, req), "CADASTRO_SUBMETIDO", "Company",
                user.companyId(), resultado.empresa().name(), detail));
        return resultado;
    }
}
