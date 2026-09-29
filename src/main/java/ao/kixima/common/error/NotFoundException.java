package ao.kixima.common.error;

/** Espelha utils/errors.js:NotFoundError — default "Recurso" tal como no Node. */
public class NotFoundException extends AppException {
    public NotFoundException() {
        this("Recurso");
    }

    public NotFoundException(String entity) {
        super(entity + " não encontrado.", 404, "NOT_FOUND");
    }
}
