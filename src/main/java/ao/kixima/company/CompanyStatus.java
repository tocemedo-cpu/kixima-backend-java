package ao.kixima.company;

/** Espelha o enum `CompanyStatus` (schema.prisma:220-225). */
public enum CompanyStatus {
    /** Cadastro em preenchimento: conta criada, perfil da empresa ainda não submetido. */
    RASCUNHO,
    PENDENTE,
    APROVADA,
    REJEITADA,
    SUSPENSA,
}
