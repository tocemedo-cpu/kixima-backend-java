package ao.kixima.common.error;

/** Espelha utils/errors.js:ValidationError. */
public class ValidationException extends AppException {
    public ValidationException(String message) {
        this(message, null);
    }

    public ValidationException(String message, Object details) {
        super(message == null || message.isBlank() ? "Dados inválidos." : message, 422, "VALIDATION_ERROR", details);
    }
}
