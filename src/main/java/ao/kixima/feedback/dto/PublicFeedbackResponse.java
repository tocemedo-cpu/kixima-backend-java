package ao.kixima.feedback.dto;

import java.util.List;

/** Espelha o retorno de feedbackService.publicar(). */
public record PublicFeedbackResponse(List<FeedbackDto> feedback, long total, double average) {
}
