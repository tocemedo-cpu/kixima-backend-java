package ao.kixima.common.error;

/** Espelha utils/errors.js:UnauthorizedError. */
public class UnauthorizedException extends AppException {
    public UnauthorizedException() {
        this("Não autenticado.");
    }

    public UnauthorizedException(String message) {
        super(message, 401, "UNAUTHORIZED");
    }
}
