package ao.kixima.report;

import ao.kixima.common.Decimais;

import ao.kixima.common.error.NotFoundException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.plan.PlanFeatureFlag;
import ao.kixima.plan.PlanService;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.RequireRole;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static ao.kixima.security.PersonaRole.COMPANY_ADMIN;
import static ao.kixima.security.PersonaRole.COMPRADOR;
import static ao.kixima.security.PersonaRole.FINANCEIRO;
import static ao.kixima.security.PersonaRole.FORNECEDOR;

/** Espelha reportsRoutes.js — estatísticas do fornecedor e relatório de conteúdo local (plano Pro). */
@RestController
@RequestMapping("/api/reports")
public class ReportsController {

    private final ReportsService reportsService;
    private final ConteudoLocalService conteudoLocalService;
    private final CompanyRepository companyRepository;
    private final PlanService planService;

    public ReportsController(ReportsService reportsService, ConteudoLocalService conteudoLocalService,
                             CompanyRepository companyRepository, PlanService planService) {
        this.reportsService = reportsService;
        this.conteudoLocalService = conteudoLocalService;
        this.companyRepository = companyRepository;
        this.planService = planService;
    }

    /** `meses` é opcional: sem ele vale a janela do plano; com ele, vale o menor dos dois. */
    @GetMapping("/fornecedor")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public Map<String, Object> fornecedor(@RequestParam(required = false) String meses) {
        return reportsService.supplierStats(CurrentUserHolder.get().companyId(), meses);
    }

    /** Relatório de conteúdo local — quem compra (operadora) é que reporta. */
    @GetMapping("/conteudo-local")
    @RequireRole({COMPANY_ADMIN, COMPRADOR, FINANCEIRO})
    @Transactional(readOnly = true)
    public Map<String, Object> conteudoLocal(@RequestParam(required = false) String de, @RequestParam(required = false) String ate) {
        String companyId = CurrentUserHolder.get().companyId();
        Company empresa = companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Empresa"));
        planService.assertFeature(empresa, PlanFeatureFlag.RELATORIO_CONTEUDO_LOCAL, "Relatório de conteúdo local");
        // conteudoLocalService.js faz TODA a aritmética com Number(): os decimais deste relatório são números.
        return (Map<String, Object>) Decimais.numerosEmProfundidade(conteudoLocalService.gerar(companyId, de, ate));
    }
}
