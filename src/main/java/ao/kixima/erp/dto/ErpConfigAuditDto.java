package ao.kixima.erp.dto;

import java.time.Instant;

public record ErpConfigAuditDto(String id, String companyId, String action, String fromErp, String toErp,
                                 String actorUserId, String actorName, String result, Instant createdAt) {
}
