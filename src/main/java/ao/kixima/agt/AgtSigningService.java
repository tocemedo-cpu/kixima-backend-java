package ao.kixima.agt;

import ao.kixima.common.error.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Espelha backend/src/services/agtSigningService.js — assinatura JWS/RS256
 * do envelope de submissão AGT (schema v2.0, SETIC-FP DS.120 §4.1.6).
 *
 * LEIA ISTO ANTES DE USAR (mesmo aviso do Node): implementado contra o
 * formato observado em amostras reais da AGT, POR LIGAR — não existe chave
 * privada nem número de validação atribuídos pela AGT nesta plataforma.
 * RECUSA-SE A FINGIR: sem a chave e o número de validação configurados,
 * {@link #exigirConfiguracao()} lança, a dizer exactamente o que falta —
 * nunca uma assinatura simulada.
 */
@Service
public class AgtSigningService {

    private final PrivateKey chavePrivada;
    private final String fonteChave;
    private final String softwareValidationNumber;
    private final String softwareId;
    private final String softwareVersion;

    public AgtSigningService(
            @Value("${kixima.agt.jws-private-key-base64:}") String jwsPrivateKeyBase64,
            @Value("${kixima.agt.jws-private-key-path:}") String jwsPrivateKeyPath,
            @Value("${kixima.agt.software-validation-number:}") String softwareValidationNumber,
            @Value("${kixima.agt.software-id:KIXIMA}") String softwareId,
            @Value("${kixima.agt.software-version:1.0}") String softwareVersion) {
        AgtPrivateKeyLoader.Resultado resultado = AgtPrivateKeyLoader.carregar(jwsPrivateKeyBase64, jwsPrivateKeyPath);
        this.chavePrivada = resultado.chave();
        this.fonteChave = resultado.fonte();
        this.softwareValidationNumber = softwareValidationNumber;
        this.softwareId = softwareId;
        this.softwareVersion = softwareVersion;
    }

    // --- Prontidão -----------------------------------------------------------

    private Map<String, String> nomeVariavelEmFalta() {
        Map<String, String> m = new LinkedHashMap<>();
        if (chavePrivada == null) {
            m.put("jwsPrivateKeyPem", "AGT_JWS_PRIVATE_KEY_BASE64 (ou o ficheiro local configurado em kixima.agt.jws-private-key-path)");
        }
        if (softwareValidationNumber == null || softwareValidationNumber.isBlank()) {
            m.put("softwareValidationNumber", "AGT_SOFTWARE_VALIDATION_NUMBER");
        }
        return m;
    }

    public List<String> emFalta() {
        return new ArrayList<>(nomeVariavelEmFalta().values());
    }

    public boolean disponivel() {
        return nomeVariavelEmFalta().isEmpty();
    }

    public void exigirConfiguracao() {
        List<String> falta = emFalta();
        if (!falta.isEmpty()) {
            throw new ServiceUnavailableException(
                    "Assinatura AGT não está configurada. Em falta: " + String.join(", ", falta)
                            + ". Sem a chave e o número de validação REAIS da AGT, nenhum payload é assinado — "
                            + "este processo não simula certificação.");
        }
    }

    /** Estado para o painel de Prontidão — mesmo formato de AgtSandboxClient.estado(). Nunca expõe a chave. */
    public Map<String, Object> estado() {
        Map<String, Object> chaveInfo = new LinkedHashMap<>();
        if (chavePrivada != null) {
            chaveInfo.put("fonte", fonteChave);
        } else {
            chaveInfo.put("fonte", "não configurada");
        }
        return AgtJson.mapa(
                "canal", "AGT_ASSINATURA",
                "disponivel", disponivel(),
                "emFalta", emFalta(),
                "chavePrivada", chaveInfo,
                "nota", disponivel()
                        ? "Configurado."
                        : "Implementado contra o formato observado em amostras da AGT, por ligar. Requer certificação e chave privada reais.");
    }

    // --- Assinatura JWS --------------------------------------------------------

    private static String base64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * JWS compacto RS256 sobre um objecto — a ordem das chaves no payload
     * assinado é a ordem de inserção do {@link Map} recebido (mesmo
     * princípio do Node: quem chama controla a forma exacta só pela ordem
     * em que constrói o objecto). Usar sempre {@link LinkedHashMap} (ou
     * {@link AgtJson#mapa}), nunca um {@link Map#of} (ordem não garantida).
     */
    public String assinarJWS(Object payloadObj) {
        exigirConfiguracao();
        String headerB64 = base64url(AgtJson.stringify(AgtJson.mapa("typ", "JOSE", "alg", "RS256")).getBytes(StandardCharsets.UTF_8));
        String payloadB64 = base64url(AgtJson.stringify(payloadObj).getBytes(StandardCharsets.UTF_8));
        byte[] assinatura = assinarBytes((headerB64 + "." + payloadB64).getBytes(StandardCharsets.UTF_8));
        return headerB64 + "." + payloadB64 + "." + base64url(assinatura);
    }

    byte[] assinarBytes(byte[] dados) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(chavePrivada);
            signature.update(dados);
            return signature.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Falha a assinar payload AGT (RSA-SHA256).", e);
        }
    }

    /**
     * {@code softwareInfo} do envelope — campos assinados: exactamente os de
     * {@code softwareInfoDetail} (productId, productVersion,
     * softwareValidationNumber), por esta ordem — spec 4.1.6.
     */
    public Map<String, Object> construirSoftwareInfo() {
        Map<String, Object> detail = AgtJson.mapa(
                "productId", softwareId,
                "productVersion", softwareVersion,
                "softwareValidationNumber", softwareValidationNumber);
        return AgtJson.mapa("softwareInfoDetail", detail, "jwsSoftwareSignature", assinarJWS(detail));
    }

    /**
     * {@code jwsDocumentSignature} — exactamente estes 8 campos, por esta
     * ordem (SETIC-FP DS.120, 4.1.6, linha {@code jwsDocumentSignature}).
     */
    public String assinarDocumento(String documentNo, String taxRegistrationNumber, String documentType,
                                    String documentDate, String customerTaxID, String customerCountry,
                                    String companyName, Map<String, Object> documentTotals) {
        return assinarJWS(AgtJson.mapa(
                "documentNo", documentNo,
                "taxRegistrationNumber", taxRegistrationNumber,
                "documentType", documentType,
                "documentDate", documentDate,
                "customerTaxID", customerTaxID,
                "customerCountry", customerCountry,
                "companyName", companyName,
                "documentTotals", documentTotals));
    }

    /** {@code documentTotals} na ordem exigida (taxPayable, netTotal, grossTotal) — usado por assinarDocumento e pelo envelope. */
    public static Map<String, Object> documentTotals(BigDecimal taxPayable, BigDecimal netTotal, BigDecimal grossTotal) {
        return AgtJson.mapa("taxPayable", taxPayable, "netTotal", netTotal, "grossTotal", grossTotal);
    }
}
