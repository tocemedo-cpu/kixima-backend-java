package ao.kixima.plan;

import java.util.function.Function;

/** Espelha os campos numéricos (ou {@code null} = ilimitado) de FEATURES usados por `assertLimite`/`limite` no Node. */
public enum PlanLimit {
    LUGARES_INCLUIDOS(PlanFeatures::lugaresIncluidos),
    DOCUMENTOS_POR_ITEM(PlanFeatures::documentosPorItem),
    IMAGENS_POR_ITEM(PlanFeatures::imagensPorItem),
    COTACOES_POR_MES(PlanFeatures::cotacoesPorMes),
    HISTORICO_RELATORIOS_MESES(PlanFeatures::historicoRelatoriosMeses);

    private final Function<PlanFeatures, Integer> getter;

    PlanLimit(Function<PlanFeatures, Integer> getter) {
        this.getter = getter;
    }

    Integer lido(PlanFeatures features) {
        return getter.apply(features);
    }
}
