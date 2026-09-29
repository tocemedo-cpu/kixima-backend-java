package ao.kixima.feedback.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Espelha SUMMARY (feedbackService.js) — inclui `approved`. Usado por
 * minhas() e pela moderação do Admin do Sistema. {@code approved} fica
 * {@code null} (omitido) na parede pública, que usa PUBLIC_SUMMARY — ver
 * {@link #semEstadoDeModeracao()}. É o único campo condicional: os restantes
 * vêm do `select` e saem sempre ({@code targetLabel: null} incluído).
 */
public record FeedbackDto(String id, FeedbackNameRef user, FeedbackNameRef company, String categoria,
                           String targetLabel, int rating, String message, boolean verified,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Boolean approved,
                           Instant createdAt) {

    /** PUBLIC_SUMMARY — a parede pública nunca mostra `approved`, é um detalhe interno de moderação. */
    public FeedbackDto semEstadoDeModeracao() {
        return new FeedbackDto(id, user, company, categoria, targetLabel, rating, message, verified, null, createdAt);
    }
}
