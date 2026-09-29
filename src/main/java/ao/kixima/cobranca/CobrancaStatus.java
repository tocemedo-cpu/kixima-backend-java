package ao.kixima.cobranca;

/** Espelha o enum Postgres "CobrancaStatus" — partilhado por PlanoCobranca e AddonCobranca. */
public enum CobrancaStatus {
    PENDENTE,
    COMPROVATIVO_ENVIADO,
    CONFIRMADA,
    CANCELADA
}
