package ao.kixima.agt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Year;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de paridade de contrato (plano, secção 4) para o troço AGT de
 * faturacaoRoutes.js exercitado neste marco: GET /agt-payload/FT/{id} (FT
 * completo, contra uma fatura real aceite via PoService) e a recusa
 * explícita "por portar" de NC/RC. A chave privada de teste é gerada aqui
 * (nunca comitada) e injectada via {@link DynamicPropertySource} — mesma
 * técnica de tests/agt-payload.test.js no Node (par de chaves RSA
 * descartável, sem nenhuma credencial real).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AgtPayloadControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String COMPANY_ADMIN_EMAIL = "admin@petroangola.co.ao";
    private static final String FORNECEDOR_EMAIL = "fornecedor@kianda.co.ao";
    private static final String SUPPLIER_TAX_ID = "AO-FOR-0001";
    private static final String PRODUCT_NAME = "Mangueira hidráulica de alta pressão 2\"";
    private static final String ESTABLISHMENT_NUMBER = "1";
    private static final String SERIES_CODE_FT = "AGTPAY-FT-TESTE";

    private static PublicKey chavePublica;

    @DynamicPropertySource
    static void chaveDeTesteAgt(DynamicPropertyRegistry registry) throws Exception {
        KeyPairGenerator gerador = KeyPairGenerator.getInstance("RSA");
        gerador.initialize(2048);
        KeyPair par = gerador.generateKeyPair();
        chavePublica = par.getPublic();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(par.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        registry.add("kixima.agt.jws-private-key-base64", () -> Base64.getEncoder().encodeToString(pem.getBytes()));
        registry.add("kixima.agt.software-validation-number", () -> "FE/00/2025/AGT-TESTE");
        registry.add("kixima.agt.establishment-number", () -> ESTABLISHMENT_NUMBER);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AgtSeriesFeRepository agtSeriesFeRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private String login(String email) throws Exception {
        var res = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    private String supplierCompanyId() {
        return jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, SUPPLIER_TAX_ID);
    }

    private String productId(String supplierCompanyId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE supplier_id = ? AND name = ?", String.class, supplierCompanyId, PRODUCT_NAME);
    }

    /** Checkout -> aprovação -> aceitação (gera a fatura), devolve o id da fatura criada. */
    private String criarFaturaAceite(String supplierCompanyId, String productId, String compradorToken,
                                      String companyAdminToken, String fornecedorToken) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "supplierCompanyId", supplierCompanyId,
                "items", List.of(Map.of("productId", productId, "quantity", 1))));
        var createRes = mockMvc.perform(post("/api/purchase-orders")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String poId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText();
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(patch("/api/purchase-orders/" + poId + "/approve").header("Authorization", "Bearer " + companyAdminToken))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(patch("/api/purchase-orders/" + poId + "/accept").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();

        return jdbcTemplate.queryForObject("SELECT id FROM invoices WHERE purchase_order_id = ?", String.class, poId);
    }

    private void semearSerieFt(String taxRegistrationNumber) {
        agtSeriesFeRepository.save(new AgtSeriesFe(UUID.randomUUID().toString(), Year.now().getValue(), "FT",
                ESTABLISHMENT_NUMBER, taxRegistrationNumber, SERIES_CODE_FT, "999999999999", "1", "999999999999",
                UUID.randomUUID().toString(), null, "1", null, null, java.time.Instant.now()));
        entityManager.flush();
        entityManager.clear();
    }

    private void verificarJWS(String jws) throws Exception {
        String[] partes = jws.split("\\.");
        assertEquals(3, partes.length);
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(chavePublica);
        sig.update((partes[0] + "." + partes[1]).getBytes());
        assertTrue(sig.verify(Base64.getUrlDecoder().decode(partes[2])));
    }

    @Test
    void payloadDeFaturaAceiteVemAssinadoComDocumentNoDaSerieAgt() throws Exception {
        String supplierCompanyId = supplierCompanyId();
        String productId = productId(supplierCompanyId);
        String supplierTaxId = jdbcTemplate.queryForObject("SELECT tax_id FROM companies WHERE id = ?", String.class, supplierCompanyId);
        semearSerieFt(supplierTaxId);

        String compradorToken = login(COMPRADOR_EMAIL);
        String companyAdminToken = login(COMPANY_ADMIN_EMAIL);
        String fornecedorToken = login(FORNECEDOR_EMAIL);
        String invoiceId = criarFaturaAceite(supplierCompanyId, productId, compradorToken, companyAdminToken, fornecedorToken);

        var res = mockMvc.perform(get("/api/faturacao/agt-payload/FT/" + invoiceId)
                        .header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("2.0"))
                .andExpect(jsonPath("$.taxRegistrationNumber").value(supplierTaxId))
                .andExpect(jsonPath("$.documents[0].documentNo").value("FT " + SERIES_CODE_FT + "/1"))
                .andExpect(jsonPath("$.documents[0].documentType").value("FT"))
                .andExpect(jsonPath("$.documents[0].lines[0].quantity").exists())
                .andReturn();

        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
        verificarJWS(body.get("softwareInfo").get("jwsSoftwareSignature").asText());
        verificarJWS(body.get("documents").get(0).get("jwsDocumentSignature").asText());

        // Idempotente: pedir de novo o mesmo payload devolve o MESMO documentNo (não consome outro número).
        mockMvc.perform(get("/api/faturacao/agt-payload/FT/" + invoiceId).header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[0].documentNo").value("FT " + SERIES_CODE_FT + "/1"));
    }

    @Test
    void payloadDeNcOuRcProcuraODocumentoPeloId() throws Exception {
        // NC e RC já têm produtor em Java (CreditNote/Payment, grupo A das lacunas) — um id desconhecido é 404, como no Node.
        String fornecedorToken = login(FORNECEDOR_EMAIL);
        mockMvc.perform(get("/api/faturacao/agt-payload/NC/qualquer-id").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("Nota de crédito")));

        mockMvc.perform(get("/api/faturacao/agt-payload/RC/qualquer-id").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("Recibo")));
    }

    @Test
    void semSerieAtribuidaRecusaGerarODocumentNo() throws Exception {
        String supplierCompanyId = supplierCompanyId();
        String productId = productId(supplierCompanyId);
        // Sem semear série AGT para este fornecedor.

        String compradorToken = login(COMPRADOR_EMAIL);
        String companyAdminToken = login(COMPANY_ADMIN_EMAIL);
        String fornecedorToken = login(FORNECEDOR_EMAIL);
        String invoiceId = criarFaturaAceite(supplierCompanyId, productId, compradorToken, companyAdminToken, fornecedorToken);

        mockMvc.perform(get("/api/faturacao/agt-payload/FT/" + invoiceId).header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("Solicitar Série")));
    }

    @Test
    void solicitarSerieSemSandboxConfiguradaDevolve503() throws Exception {
        String adminToken = login("admin@kixima.co.ao");
        mockMvc.perform(get("/api/faturacao/agt-serie-payload")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("ano", String.valueOf(Year.now().getValue()))
                        .param("tipoDocumento", "FT"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICO_INDISPONIVEL"));
    }
}
