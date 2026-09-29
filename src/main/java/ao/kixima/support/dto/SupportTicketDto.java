package ao.kixima.support.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Optional;

/**
 * Espelha a linha `SupportTicket` tal como o Prisma a devolve — todos os
 * escalares, {@code null} incluído ({@code companyId: null} para quem não tem
 * empresa, {@code assignedToId: null} enquanto ninguém assumiu) — mais o que
 * cada rota de supportRoutes.js acrescenta por cima:
 * <ul>
 *   <li>{@code statusLabel} só em {@code GET /tickets/:id};</li>
 *   <li>{@code user}/{@code company} só em {@code GET /admin/tickets} — e aí
 *       vêm SEMPRE, com {@code null} explícito quando não há
 *       ({@code user: ... : null}, {@code company: ... || null}).</li>
 * </ul>
 * Convenção dos campos {@link Optional}: Java {@code null} → chave ausente;
 * {@code Optional.empty()} → {@code "chave": null} no JSON.
 */
public record SupportTicketDto(String id, String reference, String userId, String companyId, String subject,
                                String category, String message, String status, String assignedToId,
                                Instant createdAt, Instant updatedAt,
                                @JsonInclude(JsonInclude.Include.NON_NULL) String statusLabel,
                                @JsonInclude(JsonInclude.Include.NON_NULL) Optional<SupportTicketDto.UserRef> user,
                                @JsonInclude(JsonInclude.Include.NON_NULL) Optional<String> company) {

    public record UserRef(String name, String email) {
    }

    public static SupportTicketDto semExtras(ao.kixima.support.SupportTicket t) {
        return new SupportTicketDto(t.getId(), t.getReference(), t.getUserId(), t.getCompanyId(), t.getSubject(),
                t.getCategory(), t.getMessage(), t.getStatus().name(), t.getAssignedToId(), t.getCreatedAt(),
                t.getUpdatedAt(), null, null, null);
    }

    public SupportTicketDto comStatusLabel(String label) {
        return new SupportTicketDto(id, reference, userId, companyId, subject, category, message, status,
                assignedToId, createdAt, updatedAt, label, user, company);
    }

    /** Listagem admin: as duas chaves saem sempre, a {@code null} quando não há autor/empresa. */
    public SupportTicketDto comUserECompany(UserRef user, String company) {
        return new SupportTicketDto(id, reference, userId, companyId, subject, category, message, status,
                assignedToId, createdAt, updatedAt, statusLabel, Optional.ofNullable(user), Optional.ofNullable(company));
    }
}
