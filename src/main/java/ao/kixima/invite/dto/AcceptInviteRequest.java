package ao.kixima.invite.dto;

/** Espelha acceptInviteSchema (utils/schemas.js) — name/email só usados em links antigos sem convite persistido. */
public record AcceptInviteRequest(String name, String email, String password, Boolean termsAccepted) {
}
