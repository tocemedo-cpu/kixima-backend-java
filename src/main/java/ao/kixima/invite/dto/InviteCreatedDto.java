package ao.kixima.invite.dto;

import java.time.Instant;

/** Espelha o retorno de createInvite (companyService.js) — pickInvite + companyName. */
public record InviteCreatedDto(String id, String name, String email, String role, String status,
                                Instant expiresAt, Instant acceptedAt, Instant createdAt, String companyName) {
}
