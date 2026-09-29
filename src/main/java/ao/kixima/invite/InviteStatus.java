package ao.kixima.invite;

/** Espelha o enum Postgres/Prisma `InviteStatus` (schema.prisma:407-412). */
public enum InviteStatus {
    PENDENTE,
    ACEITO,
    EXPIRADO,
    CANCELADO,
}
