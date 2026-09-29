package ao.kixima.common.error;

/**
 * Espelha utils/errors.js:ServiceUnavailableError — "recusa-se a fingir":
 * uma integração externa (AGT, Sandbox) sem credenciais reais devolve isto
 * em vez de simular uma resposta. Ver AgtSigningService (M4).
 */
public class ServiceUnavailableException extends AppException {
    public ServiceUnavailableException() {
        this("Este serviço ainda não está configurado.");
    }

    public ServiceUnavailableException(String message) {
        super(message, 503, "SERVICO_INDISPONIVEL");
    }
}
