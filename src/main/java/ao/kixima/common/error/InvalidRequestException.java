package ao.kixima.common.error;

/**
 * Espelha os {@code res.status(400).json({ error: { code: 'INVALID', message } })}
 * escritos à mão em supportRoutes.js (ticket sem assunto/mensagem, estado
 * inválido, transferência sem destino). Não existe uma classe AppError para
 * isto no Node — é um 400 literal com o código {@code INVALID}, que NÃO é o
 * 422 {@code VALIDATION_ERROR} do zod nem o 400 {@code BUSINESS_RULE_VIOLATION}.
 */
public class InvalidRequestException extends AppException {
    public InvalidRequestException(String message) {
        super(message, 400, "INVALID");
    }
}
