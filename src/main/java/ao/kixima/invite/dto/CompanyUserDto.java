package ao.kixima.invite.dto;

import java.time.Instant;

/** Espelha USER_SELECT (companyService.js). */
public record CompanyUserDto(String id, String name, String email, String role, boolean active, Instant createdAt) {
}
