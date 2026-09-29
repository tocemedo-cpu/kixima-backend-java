package ao.kixima.company;

/**
 * Espelha o enum `CompanyPlan` (schema.prisma:65-73). BASICO é histórico —
 * as empresas foram migradas para CORE; o valor fica no enum porque
 * removê-lo obrigaria a recriar o tipo Postgres, e o código trata-o como
 * sinónimo de CORE.
 */
public enum CompanyPlan {
    BASE,
    CORE,
    PRO,
    BASICO,
}
