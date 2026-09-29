package ao.kixima.common.error;

/** Espelha utils/errors.js:BusinessRuleError. */
public class BusinessRuleException extends AppException {
    public BusinessRuleException(String message) {
        super(message, 400, "BUSINESS_RULE_VIOLATION");
    }

    /** Permite a PlanRequiredException reutilizar o código 400 com um `code`/`details` próprios. */
    protected BusinessRuleException(String message, String code, Object details) {
        super(message, 400, code, details);
    }
}
