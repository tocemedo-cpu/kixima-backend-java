package ao.kixima.plan;

/**
 * Espelha uma entrada de FEATURES em backend/src/services/planService.js —
 * a matriz de funcionalidades/limites por plano. Um valor {@code null} num
 * limite significa "sem limite" (ILIMITADO no Node), nunca confundir com 0
 * ("nenhum").
 */
public record PlanFeatures(
        Integer itensNoCatalogo,
        int posicaoNaPesquisa,
        boolean selo,
        Integer lugaresIncluidos,
        boolean kits,
        boolean carregamentoEmMassa,
        Integer documentosPorItem,
        Integer imagensPorItem,
        Integer cotacoesPorMes,
        Integer historicoRelatoriosMeses,
        boolean frameworkContracts,
        boolean erpIntegration,
        boolean relatorioConteudoLocal,
        boolean apiCatalogo,
        boolean supplierComparison,
        boolean auditTrail,
        boolean categoryManagement
) {
}
