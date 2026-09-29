package ao.kixima.supplierdev;

/** Espelha o enum Postgres/Prisma `SupplierDevStatus` (schema.prisma:1878-1884). */
public enum SupplierDevStatus {
    RECEBIDA,
    EM_ANALISE,
    EM_ACOMPANHAMENTO,
    CONCLUIDA,
    REJEITADA,
}
