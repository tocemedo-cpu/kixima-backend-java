package ao.kixima.audit.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * `detail` sai como objecto/array JSON de verdade (não uma string
 * escapada) — mesmo formato do Prisma, cujo `Json?` já desserializa
 * automaticamente; a entidade guarda-o como texto bruto (ver AuditLog),
 * por isso este DTO faz esse passo antes de responder.
 */
public record AuditLogDto(String id, String action, String entityType, String entityId, String entityRef,
                           String actorId, String actorName, String actorRole, String companyId, String ip,
                           JsonNode detail, Instant createdAt) {
}
