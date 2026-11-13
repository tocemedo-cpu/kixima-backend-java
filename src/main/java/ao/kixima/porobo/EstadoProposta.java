package ao.kixima.porobo;

/** Estados de uma proposta do PO Robot (V4). Só {@code PENDENTE_REVISAO} aceita ajuste, submissão ou rejeição. */
public enum EstadoProposta {
    PENDENTE_REVISAO,
    SUBMETIDA,
    REJEITADA,
    EXPIRADA
}
