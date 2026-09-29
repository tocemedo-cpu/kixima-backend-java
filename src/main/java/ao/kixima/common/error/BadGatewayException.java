package ao.kixima.common.error;

/** 502 — uma dependência externa (o servidor de email, um gateway) respondeu mal. Espelha `res.status(err.status || 502)` em adminRoutes.js. */
public class BadGatewayException extends AppException {
    public BadGatewayException(String message) {
        super(message, 502, "BAD_GATEWAY");
    }
}
