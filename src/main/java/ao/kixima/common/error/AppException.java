package ao.kixima.common.error;

import org.springframework.http.HttpStatus;

/**
 * Espelha backend/src/utils/errors.js:AppError. O {@link GlobalExceptionHandler}
 * reproduz o envelope {@code {error:{code,message,details?}}} exactamente
 * como o errorHandler.js do backend Node.
 */
public class AppException extends RuntimeException {
    private final int statusCode;
    private final String code;
    private final transient Object details;

    public AppException(String message, int statusCode, String code) {
        this(message, statusCode, code, null);
    }

    public AppException(String message, int statusCode, String code, Object details) {
        super(message);
        this.statusCode = statusCode;
        this.code = code;
        this.details = details;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public HttpStatus getHttpStatus() {
        return HttpStatus.valueOf(statusCode);
    }

    public String getCode() {
        return code;
    }

    public Object getDetails() {
        return details;
    }
}
