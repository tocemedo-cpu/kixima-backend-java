package ao.kixima.common.error;

/** Espelha utils/errors.js:ForbiddenError. */
public class ForbiddenException extends AppException {
    public ForbiddenException() {
        this("Sem permissão para esta ação.");
    }

    public ForbiddenException(String message) {
        super(message, 403, "FORBIDDEN");
    }
}
