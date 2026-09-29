package ao.kixima.plan;

/**
 * Espelha os 4 patamares de urgência de {@code planService.estadoSubscricao}
 * (backend/src/services/planService.js). NUNCA persistido — calculado a
 * cada leitura a partir de {@code Company.planoValidoAte}, tal como no
 * Node, para nunca poder ficar dessincronizado da data real.
 */
public enum SubscriptionState {
    ATIVA,
    A_EXPIRAR,
    GRACE,
    RESTRITA,
}
