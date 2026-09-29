package ao.kixima.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Envelope de erro — espelha exactamente {@code {error:{code,message,details?}}}
 * produzido por backend/src/middleware/errorHandler.js. `details` é omitido
 * do JSON quando null (tal como o spread condicional `...(err.details ? {...} : {})`
 * no Node), nunca serializado como `"details":null`.
 */
public record ErrorResponse(Body error) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(new Body(code, message, null, null));
    }

    public static ErrorResponse of(String code, String message, Object details) {
        return new ErrorResponse(new Body(code, message, details, null));
    }

    public static ErrorResponse withStack(String code, String message, String stack) {
        return new ErrorResponse(new Body(code, message, null, stack));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Body(String code, String message, Object details, String stack) {
    }
}
