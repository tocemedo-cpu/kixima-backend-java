package ao.kixima.conversation.dto;

public record StartConversationRequest(String otherCompanyId, String contextType, String contextId) {
}
