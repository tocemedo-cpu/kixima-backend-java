package ao.kixima.feedback.dto;

public record CreateFeedbackRequest(String categoria, String targetId, Integer rating, String message) {
}
