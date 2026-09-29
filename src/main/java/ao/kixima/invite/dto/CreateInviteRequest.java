package ao.kixima.invite.dto;

/** Espelha createInviteSchema (utils/schemas.js): role em {COMPRADOR, FORNECEDOR, FINANCEIRO}. */
public record CreateInviteRequest(String role, String name, String email) {
}
