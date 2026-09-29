package ao.kixima.security;

/**
 * Espelha o enum `PersonaRole` (schema.prisma:236-242) — os 5 perfis do
 * KIXIMA. ADMIN_SISTEMA é interno à KIXIMA, não pertence a nenhuma empresa
 * transacionadora (companyId nulo em User).
 */
public enum PersonaRole {
    COMPRADOR,
    COMPANY_ADMIN,
    FORNECEDOR,
    FINANCEIRO,
    ADMIN_SISTEMA,
}
