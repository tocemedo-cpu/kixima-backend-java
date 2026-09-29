package ao.kixima.invite.dto;

/** Espelha o retorno de resolveInvite (companyService.js) — o que o convidado vê antes de aceitar. */
public record ResolvedInviteDto(String companyName, String companyType, String role, String name, String email) {
}
