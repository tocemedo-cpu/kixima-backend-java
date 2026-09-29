package ao.kixima.plan;

import java.util.function.Predicate;

/** Espelha os campos booleanos de FEATURES usados por `assertFeature`/`hasFeature` no Node. */
public enum PlanFeatureFlag {
    SELO(PlanFeatures::selo),
    KITS(PlanFeatures::kits),
    CARREGAMENTO_EM_MASSA(PlanFeatures::carregamentoEmMassa),
    FRAMEWORK_CONTRACTS(PlanFeatures::frameworkContracts),
    ERP_INTEGRATION(PlanFeatures::erpIntegration),
    RELATORIO_CONTEUDO_LOCAL(PlanFeatures::relatorioConteudoLocal),
    API_CATALOGO(PlanFeatures::apiCatalogo),
    SUPPLIER_COMPARISON(PlanFeatures::supplierComparison),
    AUDIT_TRAIL(PlanFeatures::auditTrail),
    CATEGORY_MANAGEMENT(PlanFeatures::categoryManagement);

    private final Predicate<PlanFeatures> getter;

    PlanFeatureFlag(Predicate<PlanFeatures> getter) {
        this.getter = getter;
    }

    boolean lido(PlanFeatures features) {
        return getter.test(features);
    }
}
