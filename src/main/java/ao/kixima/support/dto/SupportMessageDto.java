package ao.kixima.support.dto;

import java.time.Instant;

/**
 * A linha `SupportMessage` tal como o Prisma a devolve (findMany/create sem
 * select): todas as colunas, {@code null} incluído — {@code attachmentUrl},
 * {@code attachmentName} e {@code readAt} saem a {@code null}, não desaparecem.
 */
public record SupportMessageDto(String id, String ticketId, String authorId, String authorRole, String body,
                                 String attachmentUrl, String attachmentName, Instant readAt, Instant createdAt) {

    public static SupportMessageDto de(ao.kixima.support.SupportMessage m) {
        return new SupportMessageDto(m.getId(), m.getTicketId(), m.getAuthorId(), m.getAuthorRole().name(), m.getBody(),
                m.getAttachmentUrl(), m.getAttachmentName(), m.getReadAt(), m.getCreatedAt());
    }
}
