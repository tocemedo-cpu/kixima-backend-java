package ao.kixima.agt;

import java.util.List;

/**
 * Espelha agtSandboxClient.js:AgtApiError — erro de negócio devolvido pela
 * AGT (a resposta não passa no critério de sucesso do endpoint). É um erro
 * INTERNO de transporte, não um {@code AppException}: quem chama
 * (AgtSeriesService, AgtPayloadService) é que decide traduzi-lo para
 * {@link ao.kixima.common.error.AgtRecusadoException} (502), acrescentando
 * o pedido que foi mesmo enviado.
 */
public class AgtApiException extends RuntimeException {

    private final String endpoint;
    private final String resultCode;
    private final List<String> errorList;
    private final Object respostaBruta;

    public AgtApiException(String endpoint, String resultCode, List<String> errorList, Object respostaBruta) {
        super("AGT recusou o pedido a \"" + endpoint + "\" (resultCode=" + resultCode + "): " + (errorList == null ? "[]" : errorList));
        this.endpoint = endpoint;
        this.resultCode = resultCode;
        this.errorList = errorList == null ? List.of() : errorList;
        this.respostaBruta = respostaBruta;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getResultCode() {
        return resultCode;
    }

    public List<String> getErrorList() {
        return errorList;
    }

    public Object getRespostaBruta() {
        return respostaBruta;
    }
}
