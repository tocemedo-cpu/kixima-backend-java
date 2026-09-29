package ao.kixima.common.error;

/**
 * Espelha utils/errors.js:PlanRequiredError — código próprio ("PLANO_INSUFICIENTE")
 * para a interface oferecer o caminho para a página de subscrição sem ter de
 * reconhecer o muro pelo texto da mensagem. `planoNecessario` pode ser null,
 * tal como no Node (details.planoNecessario: null).
 */
public class PlanRequiredException extends BusinessRuleException {
    public PlanRequiredException(String message, String planoNecessario) {
        super(message, "PLANO_INSUFICIENTE", new Details(planoNecessario));
    }

    public record Details(String planoNecessario) {
    }
}
