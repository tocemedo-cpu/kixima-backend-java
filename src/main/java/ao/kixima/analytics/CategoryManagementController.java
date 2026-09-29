package ao.kixima.analytics;

import ao.kixima.common.error.NotFoundException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.discount.DiscountThresholdService;
import ao.kixima.plan.PlanFeatureFlag;
import ao.kixima.plan.PlanService;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ao.kixima.security.PersonaRole.COMPANY_ADMIN;
import static ao.kixima.security.PersonaRole.COMPRADOR;
import static ao.kixima.security.PersonaRole.FINANCEIRO;

/**
 * Espelha o troço "Lado da empresa" de backend/src/routes/categoryManagementRoutes.js
 * ({@code /analise}, {@code /produtos/:id/media-mensal}) — os patamares do lado
 * KIXIMA vivem em {@link ao.kixima.discount.DiscountThresholdController}.
 */
@RestController
@RequestMapping("/api/category-management")
public class CategoryManagementController {

    private final CompanyRepository companyRepository;
    private final PlanService planService;
    private final CategoryAnalyticsService categoryAnalyticsService;
    private final DiscountThresholdService discountThresholdService;
    private final AiRecommendationService aiRecommendationService;

    public CategoryManagementController(CompanyRepository companyRepository, PlanService planService,
                                        CategoryAnalyticsService categoryAnalyticsService,
                                        DiscountThresholdService discountThresholdService,
                                        AiRecommendationService aiRecommendationService) {
        this.companyRepository = companyRepository;
        this.planService = planService;
        this.categoryAnalyticsService = categoryAnalyticsService;
        this.discountThresholdService = discountThresholdService;
        this.aiRecommendationService = aiRecommendationService;
    }

    private Company empresaComFuncionalidade() {
        Company empresa = companyRepository.findById(CurrentUserHolder.get().companyId()).orElseThrow(() -> new NotFoundException("Empresa"));
        planService.assertFeature(empresa, PlanFeatureFlag.CATEGORY_MANAGEMENT, "Category Management");
        return empresa;
    }

    /** `de.setMonth(de.getMonth() - meses)` — os últimos N meses até agora. */
    static CategoryAnalyticsService.Janela janelaMeses(int meses) {
        Instant ate = Instant.now();
        Instant de = ate.atZone(ZoneOffset.UTC).minusMonths(meses).toInstant();
        return new CategoryAnalyticsService.Janela(de, ate);
    }

    @GetMapping("/analise")
    @RequireRole({COMPANY_ADMIN, COMPRADOR, FINANCEIRO})
    public Map<String, Object> analise(@RequestParam(required = false) String meses, @RequestParam(required = false) String recomendacao) {
        Company empresa = empresaComFuncionalidade();
        int n = 12;
        if (meses != null && !meses.isBlank()) {
            try {
                int pedido = (int) Double.parseDouble(meses.trim());
                if (pedido != 0) n = pedido;
            } catch (NumberFormatException ignored) {
                // Number('abc') || 12
            }
        }
        CategoryAnalyticsService.Janela janela = janelaMeses(n);
        Map<String, Object> volume = categoryAnalyticsService.volumePorCategoria(empresa.getId(), janela);
        List<DiscountThresholdService.Patamar> thresholds = discountThresholdService.listar(true).stream().map(DiscountThresholdService.Patamar::de).toList();
        double total = (double) volume.get("total");
        Map<String, Object> thresholdInfo = discountThresholdService.proximoThreshold(total, thresholds);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> categorias = (List<Map<String, Object>>) volume.get("categorias");
        List<Map<String, Object>> oportunidades = categoryAnalyticsService.oportunidadesConsolidacao(empresa.getId(), janela,
                thresholds.stream().map(t -> new CategoryAnalyticsService.Patamar(t.minVolumeUsd(), t.discountPercent(), t.ativo())).toList());

        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("texto", null);
        rec.put("motivo", "Não pedida.");
        if (!"0".equals(recomendacao)) {
            rec = aiRecommendationService.gerar(empresa.getName(), total, categorias, thresholdInfo, oportunidades);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodo", volume.get("periodo"));
        out.put("volumeAtualUsd", total);
        out.put("categorias", categorias);
        out.put("descontoAtual", thresholdInfo.get("descontoAtual"));
        out.put("thresholdAtual", thresholdInfo.get("thresholdAtual"));
        out.put("proximoThreshold", thresholdInfo.get("proximoThreshold"));
        out.put("faltamUsd", thresholdInfo.get("faltamUsd"));
        out.put("poupancaPotencialUsd", thresholdInfo.get("poupancaPotencialUsd"));
        out.put("oportunidadesConsolidacao", oportunidades);
        out.put("recomendacao", rec);
        return out;
    }

    @GetMapping("/produtos/{productId}/media-mensal")
    @RequireRole({COMPANY_ADMIN, COMPRADOR, FINANCEIRO})
    public Map<String, Object> mediaMensal(@PathVariable String productId) {
        Company empresa = empresaComFuncionalidade();
        return CategoryAnalyticsService.mediaMensalOut(categoryAnalyticsService.mediaMensalPorProduto(empresa.getId(), productId));
    }
}
