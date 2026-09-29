package ao.kixima.audit.dto;

import java.util.List;

/** Espelha o retorno de `auditService.list()`: `{ items, total, page, pages, actions }`. */
public record AuditLogListResponse(List<AuditLogDto> items, long total, int page, int pages, List<AuditActionCount> actions) {
}
