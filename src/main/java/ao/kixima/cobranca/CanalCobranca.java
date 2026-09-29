package ao.kixima.cobranca;

/** Espelha o enum Postgres "CanalCobranca" — por onde uma cobrança de subscrição/add-on se paga. */
public enum CanalCobranca {
    TRANSFERENCIA_MANUAL,
    EMIS_MULTICAIXA,
    PAYPAY,
    BAI,
    BFA,
    STANDARD_BANK_ANGOLA
}
