package ao.kixima.agt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Espelha tests/agt-payload.test.js/agt-signing-sem-configuracao.test.js:
 * gera um par de chaves RSA de teste (mesma técnica do Node — nenhuma
 * chave real é comitada), assina e verifica com a chave PÚBLICA, e
 * confirma a ordem exacta dos campos assinados — é essa ordem, byte a
 * byte, que a AGT valida (SETIC-FP DS.120 §4.1.6), não só o conteúdo.
 */
class AgtSigningServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static PublicKey chavePublica;
    private static AgtSigningService service;

    @BeforeAll
    static void gerarChaveDeTeste() throws Exception {
        KeyPairGenerator gerador = KeyPairGenerator.getInstance("RSA");
        gerador.initialize(2048);
        KeyPair par = gerador.generateKeyPair();
        chavePublica = par.getPublic();

        // Encoding PKCS#8 nativo do Java — embrulha em PEM, depois em Base64,
        // tal como AGT_JWS_PRIVATE_KEY_BASE64 é lido em produção.
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(par.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String base64 = Base64.getEncoder().encodeToString(pem.getBytes());

        service = new AgtSigningService(base64, "", "FE/00/2025/AGT-TESTE", "KIXIMA", "1.0");
    }

    private static boolean verificarJWS(String jws, JsonNode[] headerOut, JsonNode[] payloadOut) throws Exception {
        String[] partes = jws.split("\\.");
        assertEquals(3, partes.length);
        byte[] assinatura = Base64.getUrlDecoder().decode(partes[2]);
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(chavePublica);
        sig.update((partes[0] + "." + partes[1]).getBytes());
        boolean ok = sig.verify(assinatura);
        headerOut[0] = MAPPER.readTree(Base64.getUrlDecoder().decode(partes[0]));
        payloadOut[0] = MAPPER.readTree(Base64.getUrlDecoder().decode(partes[1]));
        return ok;
    }

    @Test
    void disponivelQuandoConfigurado() {
        assertTrue(service.disponivel());
        assertTrue(service.emFalta().isEmpty());
    }

    @Test
    void assinaJwsComCabecalhoEAssinaturaValidos() throws Exception {
        JsonNode[] header = new JsonNode[1];
        JsonNode[] payload = new JsonNode[1];
        boolean ok = verificarJWS(service.assinarJWS(Map.of("a", 1)), header, payload);

        assertTrue(ok);
        assertEquals("JOSE", header[0].get("typ").asText());
        assertEquals("RS256", header[0].get("alg").asText());
    }

    @Test
    void construirSoftwareInfoAssinaExactamenteOsTresCamposNestaOrdem() throws Exception {
        Map<String, Object> softwareInfo = service.construirSoftwareInfo();
        String jws = (String) softwareInfo.get("jwsSoftwareSignature");

        JsonNode[] header = new JsonNode[1];
        JsonNode[] payload = new JsonNode[1];
        assertTrue(verificarJWS(jws, header, payload));

        Iterator<String> campos = payload[0].fieldNames();
        assertEquals("productId", campos.next());
        assertEquals("productVersion", campos.next());
        assertEquals("softwareValidationNumber", campos.next());
        assertFalse(campos.hasNext());

        assertEquals("KIXIMA", payload[0].get("productId").asText());
        assertEquals("1.0", payload[0].get("productVersion").asText());
        assertEquals("FE/00/2025/AGT-TESTE", payload[0].get("softwareValidationNumber").asText());
    }

    @Test
    void assinarDocumentoUsaExactamenteOsOitoCamposDaSpecNestaOrdem() throws Exception {
        Map<String, Object> totals = AgtSigningService.documentTotals(
                new BigDecimal("33600.00"), new BigDecimal("240000.00"), new BigDecimal("273600.00"));
        String jws = service.assinarDocumento("FT AGTPAY-FT-TESTE/1", "AO-FOR-0001", "FT", "2026-09-24",
                "999999999", "AO", "Petro Angola Operações, Lda", totals);

        JsonNode[] header = new JsonNode[1];
        JsonNode[] payload = new JsonNode[1];
        assertTrue(verificarJWS(jws, header, payload));

        List<String> esperado = List.of("documentNo", "taxRegistrationNumber", "documentType", "documentDate",
                "customerTaxID", "customerCountry", "companyName", "documentTotals");
        List<String> real = new java.util.ArrayList<>();
        payload[0].fieldNames().forEachRemaining(real::add);
        assertEquals(esperado, real);

        // Números sem zeros à direita — mesma forma que JSON.stringify(Number) no Node.
        JsonNode dt = payload[0].get("documentTotals");
        assertEquals("33600", dt.get("taxPayable").toString());
        assertEquals("240000", dt.get("netTotal").toString());
        assertEquals("273600", dt.get("grossTotal").toString());
    }

    @Test
    void semChaveConfiguradaRecusaAssinar() {
        AgtSigningService semChave = new AgtSigningService("", "", "", "KIXIMA", "1.0");
        assertFalse(semChave.disponivel());
        assertEquals(2, semChave.emFalta().size());
        org.junit.jupiter.api.Assertions.assertThrows(
                ao.kixima.common.error.ServiceUnavailableException.class,
                () -> semChave.assinarJWS(Map.of("a", 1)));
    }
}
