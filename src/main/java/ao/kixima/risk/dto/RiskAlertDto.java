package ao.kixima.risk.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Optional;

/**
 * A linha `RiskAlert` tal como o Prisma a devolve (escalares, {@code null}
 * incluído — {@code reviewedById}/{@code reviewedAt}/{@code decision} nulos
 * enquanto ninguém reviu) mais {@code conversation}, que só listarAlertas()
 * acrescenta — e aí sempre, a {@code null} quando a conversa já não existe
 * ({@code c ? {...} : null}). Java {@code null} → chave ausente;
 * {@code Optional.empty()} → {@code null} explícito.
 */
public record RiskAlertDto(String id, String conversationId, String messageId, String level, String reason,
                            JsonNode signals, JsonNode context, String status, String reviewedById, Instant reviewedAt,
                            String decision, Instant createdAt,
                            @JsonInclude(JsonInclude.Include.NON_NULL) Optional<ConversationRef> conversation) {

    public record ConversationRef(String id, String buyerCompanyId, String supplierCompanyId,
                                   String buyerCompany, String supplierCompany) {
    }
}
