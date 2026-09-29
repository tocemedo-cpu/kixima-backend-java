package ao.kixima.common.error;

/** Espelha utils/errors.js:ConflictError. */
public class ConflictException extends AppException {
    public ConflictException() {
        this("Conflito de estado.");
    }

    public ConflictException(String message) {
        super(message, 409, "CONFLICT");
    }
}
