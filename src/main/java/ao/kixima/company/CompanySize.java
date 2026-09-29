package ao.kixima.company;

/**
 * Espelha o enum `CompanySize` (schema.prisma:30-35) — critério MPME
 * angolano (Lei n.º 30/11). GRANDE exige o plano PRO.
 */
public enum CompanySize {
    MICRO,
    PEQUENA,
    MEDIA,
    GRANDE,
}
