package ao.kixima.common.error;

import java.util.List;

/**
 * Espelha utils/errors.js:AgtRecusadoError — a AGT recebeu o pedido mas
 * recusou-o (resultCode != "0"). 502: não é um erro nosso, é um serviço
 * externo que respondeu com uma recusa — distinto de 4xx (erro nosso no
 * pedido) e de 503 (nem configurado ainda, ver ServiceUnavailableException).
 *
 * `errorList` da AGT viaja tal e qual em `details`, sem se resumir/traduzir.
 * `pedido` é o próprio pedido construído e assinado, tal como foi submetido
 * — sem isto, uma recusa não dava forma de ver o que tinha sido enviado.
 * `respostaBruta` é o corpo COMPLETO da resposta da AGT, não só o resumo.
 */
public class AgtRecusadoException extends AppException {
    public AgtRecusadoException(String message, String endpoint, String resultCode,
                                 List<String> errorList, Object respostaBruta, Object pedido) {
        super(message, 502, "AGT_RECUSOU",
                new Details(endpoint, resultCode, errorList, respostaBruta, pedido));
    }

    public record Details(String endpoint, String resultCode, List<String> errorList,
                           Object respostaBruta, Object pedido) {
    }
}
