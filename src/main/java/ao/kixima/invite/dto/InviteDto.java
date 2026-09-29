package ao.kixima.invite.dto;

import java.time.Instant;

/** Espelha `pickInvite` (companyService.js) — nunca inclui o `token`. */
public record InviteDto(String id, String name, String email, String role, String status,
                         Instant expiresAt, Instant acceptedAt, Instant createdAt) {
}
