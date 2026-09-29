package ao.kixima.conversation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Optional;

/**
 * A linha `Conversation` tal como o Prisma a devolve (escalares, {@code null}
 * incluído — {@code contextType}/{@code contextId} nulos saem a null) mais o
 * que listarConversas() acrescenta: {@code counterpart} e {@code lastMessage},
 * SEMPRE presentes na listagem ({@code || null}) e ausentes nas outras rotas
 * (criar, obter, conversa sinalizada). Java {@code null} → chave ausente;
 * {@code Optional.empty()} → {@code null} explícito.
 */
public record ConversationDto(String id, String buyerCompanyId, String supplierCompanyId, String contextType,
                               String contextId, String status, String createdById, Instant createdAt, Instant updatedAt,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Optional<CounterpartDto> counterpart,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Optional<ConversationMessageDto> lastMessage) {

    public record CounterpartDto(String id, String name, String logoUrl) {
    }

    public static ConversationDto semExtras(ao.kixima.conversation.Conversation c) {
        return new ConversationDto(c.getId(), c.getBuyerCompanyId(), c.getSupplierCompanyId(), c.getContextType(),
                c.getContextId(), c.getStatus().name(), c.getCreatedById(), c.getCreatedAt(), c.getUpdatedAt(), null, null);
    }

    public ConversationDto comResumo(CounterpartDto counterpart, ConversationMessageDto lastMessage) {
        return new ConversationDto(id, buyerCompanyId, supplierCompanyId, contextType, contextId, status, createdById,
                createdAt, updatedAt, Optional.ofNullable(counterpart), Optional.ofNullable(lastMessage));
    }
}
