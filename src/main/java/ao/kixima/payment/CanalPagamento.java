package ao.kixima.payment;

/**
 * Espelha o enum Postgres/Prisma `CanalPagamento` — por onde o dinheiro
 * entrou. TRANSFERENCIA_MANUAL é o que sempre existiu: um canal automático
 * que falhe não pode deixar ninguém sem forma de pagar.
 */
public enum CanalPagamento {
    TRANSFERENCIA_MANUAL,
    REFERENCIA_BANCARIA,
    MULTICAIXA_EXPRESS,
    ERP,
}
