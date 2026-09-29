package ao.kixima.agt;

import ao.kixima.common.error.ServiceUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Espelha backend/src/services/agtSandboxClient.js — cliente REST da
 * Sandbox/homologação da AGT (e-Fatura). Módulo independente, focado
 * ESTRITAMENTE na comunicação de rede: gera os 3 tokens JWS exigidos pela
 * Sandbox e chama os endpoints. Não constrói o payload de negócio — isso é
 * AgtPayloadService/AgtSeriesService.
 *
 * PORQUÊ NÃO REAPROVEITA AgtSigningService: aquele assina objectos JSON
 * (payload = JSON.stringify(objecto), schema v2.0/§4.1.6). A Sandbox REST
 * aqui exige duas das três assinaturas como STRING concatenada por "|" — um
 * payload byte a byte diferente, logo uma assinatura diferente (confirmado
 * pela documentação oficial da Sandbox, não uma suposição — ver o
 * comentário completo no ficheiro Node).
 *
 * ASSUNÇÕES A CONFIRMAR CONTRA A DOCUMENTAÇÃO DA SANDBOX ANTES DE PRODUÇÃO
 * (mesmas do Node, não resolvidas aqui nem lá): autenticação HTTP Basic;
 * verbo de consultarFactura/listarFacturas (GET+query); serialização de
 * {@code documentTotals} dentro da string assinada como JSON.
 *
 * RECUSA-SE A FINGIR: sem a chave privada, o número de validação e as
 * credenciais da Sandbox configuradas, nenhum método aqui chama a rede.
 */
@Service
public class AgtSandboxClient {

    private final PrivateKey chavePrivada;
    private final String softwareValidationNumber;
    private final String softwareId;
    private final String softwareVersion;
    private final String sandboxUsername;
    private final String sandboxPassword;
    private final String ambiente;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public AgtSandboxClient(
            @Value("${kixima.agt.jws-private-key-base64:}") String jwsPrivateKeyBase64,
            @Value("${kixima.agt.jws-private-key-path:}") String jwsPrivateKeyPath,
            @Value("${kixima.agt.software-validation-number:}") String softwareValidationNumber,
            @Value("${kixima.agt.software-id:KIXIMA}") String softwareId,
            @Value("${kixima.agt.software-version:1.0}") String softwareVersion,
            @Value("${kixima.agt.sandbox-username:}") String sandboxUsername,
            @Value("${kixima.agt.sandbox-password:}") String sandboxPassword,
            @Value("${kixima.agt.env:hml}") String ambiente,
            ObjectMapper objectMapper) {
        this.chavePrivada = AgtPrivateKeyLoader.carregar(jwsPrivateKeyBase64, jwsPrivateKeyPath).chave();
        this.softwareValidationNumber = softwareValidationNumber;
        this.softwareId = softwareId;
        this.softwareVersion = softwareVersion;
        this.sandboxUsername = sandboxUsername;
        this.sandboxPassword = sandboxPassword;
        this.ambiente = ambiente;
        this.objectMapper = objectMapper;
    }

    // --- Prontidão -----------------------------------------------------------

    private Map<String, String> nomeVariavelEmFalta() {
        Map<String, String> m = new LinkedHashMap<>();
        if (chavePrivada == null) m.put("jwsPrivateKeyPem", "AGT_JWS_PRIVATE_KEY_BASE64");
        if (softwareValidationNumber == null || softwareValidationNumber.isBlank()) m.put("softwareValidationNumber", "AGT_SOFTWARE_VALIDATION_NUMBER");
        if (sandboxUsername == null || sandboxUsername.isBlank()) m.put("sandboxUsername", "AGT_SANDBOX_USERNAME");
        if (sandboxPassword == null || sandboxPassword.isBlank()) m.put("sandboxPassword", "AGT_SANDBOX_PASSWORD");
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
                    "Ligação à Sandbox da AGT não está configurada. Em falta: " + String.join(", ", falta)
                            + ". Sem a chave privada e as credenciais REAIS da Sandbox, nenhum pedido é enviado — "
                            + "este cliente não simula respostas da AGT.");
        }
    }

    public Map<String, Object> estado() {
        return AgtJson.mapa(
                "canal", "AGT_SANDBOX",
                "disponivel", disponivel(),
                "emFalta", emFalta(),
                "nota", disponivel() ? "Configurado." : "Implementado contra a documentação da Sandbox de homologação, por ligar. Requer credenciais reais.");
    }

    // --- JWS (Base64URL, RS256) sobre conteúdo bruto (não-JSON.stringify) -------

    private static String base64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Assina bytes já prontos (string pipe-delimited OU JSON já serializado) — não decide a forma, só assina. */
    public String assinarJWS(String conteudoBruto) {
        exigirConfiguracao();
        String headerB64 = base64url(AgtJson.stringify(AgtJson.mapa("typ", "JOSE", "alg", "RS256")).getBytes(StandardCharsets.UTF_8));
        String payloadB64 = base64url(conteudoBruto.getBytes(StandardCharsets.UTF_8));
        try {
            java.security.Signature signature = java.security.Signature.getInstance("SHA256withRSA");
            signature.initSign(chavePrivada);
            signature.update((headerB64 + "." + payloadB64).getBytes(StandardCharsets.UTF_8));
            byte[] assinatura = signature.sign();
            return headerB64 + "." + payloadB64 + "." + base64url(assinatura);
        } catch (Exception e) {
            throw new IllegalStateException("Falha a assinar payload da Sandbox AGT (RSA-SHA256).", e);
        }
    }

    public record SoftwareAssinado(Map<String, Object> softwareInfoDetail, String jwsSoftwareSignature) {
    }

    /** jwsSoftwareSignature — assina o OBJECTO softwareInfoDetail (JSON): productId, productVersion, softwareValidationNumber, por esta ordem. */
    public SoftwareAssinado assinarSoftware() {
        Map<String, Object> detail = AgtJson.mapa(
                "productId", softwareId,
                "productVersion", softwareVersion,
                "softwareValidationNumber", softwareValidationNumber);
        return new SoftwareAssinado(detail, assinarJWS(AgtJson.stringify(detail)));
    }

    private static String textoDe(Object valor) {
        if (valor == null) return "";
        return (valor instanceof Map || valor instanceof List) ? AgtJson.stringify(valor) : String.valueOf(valor);
    }

    /** jwsDocumentSignature — string concatenada por "|": documentNo, taxRegistrationNumber, documentType, documentDate, customerTaxID, customerCountry, companyName, documentTotals. */
    public String assinarDocumento(String documentNo, String taxRegistrationNumber, String documentType, String documentDate,
                                    String customerTaxID, String customerCountry, String companyName, Object documentTotals) {
        String texto = String.join("|", documentNo, taxRegistrationNumber, documentType, documentDate,
                customerTaxID, customerCountry, companyName, textoDe(documentTotals));
        return assinarJWS(texto);
    }

    /** jwsSignature — string {@code taxRegistrationNumber|submissionUUID}. */
    public String assinarSolicitacao(String taxRegistrationNumber, String submissionUUID) {
        return assinarJWS(taxRegistrationNumber + "|" + submissionUUID);
    }

    // --- Comunicação HTTP ---------------------------------------------------------

    private String cabecalhoAutenticacao() {
        String credenciais = Base64.getEncoder().encodeToString((sandboxUsername + ":" + sandboxPassword).getBytes(StandardCharsets.UTF_8));
        return "Basic " + credenciais;
    }

    private URI urlDe(String nomeEndpoint, Map<String, Object> query) {
        String base = AgtEndpoints.resolve(ambiente, nomeEndpoint);
        if (query == null || query.isEmpty()) return URI.create(base);
        StringBuilder sb = new StringBuilder(base).append('?');
        boolean primeiro = true;
        for (var entrada : query.entrySet()) {
            if (entrada.getValue() == null || entrada.getValue().toString().isEmpty()) continue;
            if (!primeiro) sb.append('&');
            sb.append(URLEncoder.encode(entrada.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entrada.getValue().toString(), StandardCharsets.UTF_8));
            primeiro = false;
        }
        return URI.create(sb.toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pedido(String nomeEndpoint, String method, Object body, Map<String, Object> query, Predicate<Map<String, Object>> sucesso) {
        exigirConfiguracao();
        URI uri = urlDe(nomeEndpoint, query);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", cabecalhoAutenticacao());
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(AgtJson.stringify(body), StandardCharsets.UTF_8);
        builder.method(method, publisher);

        HttpResponse<String> resposta;
        try {
            resposta = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Falha de rede a comunicar com a Sandbox AGT (" + nomeEndpoint + ").", e);
        }

        Map<String, Object> dados = null;
        String corpo = resposta.body();
        if (corpo != null && !corpo.isBlank()) {
            try {
                dados = objectMapper.readValue(corpo, Map.class);
            } catch (Exception ignorado) {
                // corpo não é JSON — dados fica null, mesmo tratamento do Node (try/catch silencioso).
            }
        }

        if (resposta.statusCode() < 200 || resposta.statusCode() >= 300) {
            throw new AgtApiException(nomeEndpoint, resultCodeDe(dados, String.valueOf(resposta.statusCode())), extrairDetalhesErro(dados), dados);
        }
        boolean ehSucesso = dados == null || (sucesso != null ? sucesso.test(dados) : "0".equals(resultCodeDe(dados, null)));
        if (!ehSucesso) {
            throw new AgtApiException(nomeEndpoint, resultCodeDe(dados, null), extrairDetalhesErro(dados), dados);
        }
        return dados;
    }

    private static String resultCodeDe(Map<String, Object> dados, String fallback) {
        if (dados == null || dados.get("resultCode") == null) return fallback;
        return String.valueOf(dados.get("resultCode"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> extrairDetalhesErro(Map<String, Object> dados) {
        if (dados == null) return List.of();
        Object documentStatusList = dados.get("documentStatusList");
        List<String> errosDocumento = new ArrayList<>();
        if (documentStatusList instanceof List<?> lista) {
            for (Object docObj : lista) {
                if (docObj instanceof Map<?, ?> doc) {
                    Object errorList = doc.get("errorList");
                    if (errorList instanceof List<?> erros) {
                        for (Object erro : erros) {
                            if (erro instanceof Map<?, ?> e) {
                                errosDocumento.add(e.get("idError") + ": " + e.get("descriptionError"));
                            }
                        }
                    }
                }
            }
        }
        if (!errosDocumento.isEmpty()) return errosDocumento;

        Object errorList = dados.get("errorList") != null ? dados.get("errorList") : dados.get("requestErrorList");
        List<String> out = new ArrayList<>();
        if (errorList instanceof List<?> lista) {
            for (Object e : lista) {
                if (e != null) out.add(String.valueOf(e));
            }
        }
        return out;
    }

    /**
     * A AGT não é consistente na forma como assinala "sem erros" —
     * confirmam-se tanto {@code []} como {@code [""]} em respostas que não
     * são recusas reais. Só conta como recusa uma entrada com conteúdo.
     */
    private static boolean semErrosReais(Object errorList) {
        if (!(errorList instanceof List<?> lista)) return true;
        for (Object e : lista) {
            if (e != null && !String.valueOf(e).isBlank()) return false;
        }
        return true;
    }

    /**
     * POST /registarFactura — CONFIRMADO em produção: a resposta real NÃO
     * traz {@code resultCode} quando aceita, só {@code requestID}. O sinal
     * real de sucesso é ter {@code requestID}; {@code errorList} só reforça
     * a recusa quando tem conteúdo real.
     */
    public Map<String, Object> registarFactura(Map<String, Object> documento) {
        return pedido("registarFactura", "POST", documento, null,
                dados -> dados.get("requestID") != null && semErrosReais(dados.get("errorList")));
    }

    /**
     * POST /solicitarSerie — CONFIRMADO em produção (HML): a AGT devolve
     * resultCode=1 mesmo quando ACEITA; o sinal real é
     * {@code seriesFEResult.seriesCode} presente.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> solicitarSerie(Map<String, Object> documento) {
        return pedido("solicitarSerie", "POST", documento, null, dados -> {
            Object result = dados.get("seriesFEResult");
            return result instanceof Map<?, ?> m && m.get("seriesCode") != null;
        });
    }

    /**
     * POST /obterEstado — CORRIGIDO: não é GET+query (405 confirmado); é
     * POST com um envelope assinado, mesmo formato do registarFactura/
     * solicitarSerie (construído por quem chama).
     */
    public Map<String, Object> obterEstado(Map<String, Object> envelope) {
        return pedido("obterEstado", "POST", envelope, null, null);
    }

    /** GET /consultarFactura — detalhe de uma fatura já registada. */
    public Map<String, Object> consultarFactura(String documentNo, String taxRegistrationNumber) {
        return pedido("consultarFactura", "GET", null,
                AgtJson.mapa("documentNo", documentNo, "taxRegistrationNumber", taxRegistrationNumber), null);
    }

    /** GET /listarFacturas — listagem paginada, filtrável por período. */
    public Map<String, Object> listarFacturas(String taxRegistrationNumber, String dataInicio, String dataFim, Integer pagina, Integer tamanhoPagina) {
        return pedido("listarFacturas", "GET", null,
                AgtJson.mapa("taxRegistrationNumber", taxRegistrationNumber, "dataInicio", dataInicio,
                        "dataFim", dataFim, "pagina", pagina, "tamanhoPagina", tamanhoPagina),
                null);
    }
}
