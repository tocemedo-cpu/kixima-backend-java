package ao.kixima.conversation.dto;

import java.time.Instant;

public record ConversationMessageDto(String id, String conversationId, String senderId, String senderCompanyId,
                                      String body, String attachmentUrl, String attachmentName, Instant readAt,
                                      Instant createdAt) {

    public static ConversationMessageDto de(ao.kixima.conversation.ConversationMessage m) {
        return new ConversationMessageDto(m.getId(), m.getConversationId(), m.getSenderId(), m.getSenderCompanyId(),
                m.getBody(), m.getAttachmentUrl(), m.getAttachmentName(), m.getReadAt(), m.getCreatedAt());
    }
}
