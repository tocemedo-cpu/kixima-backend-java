package ao.kixima.company;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Espelha companyService.js/companyRoutes.js: cadastro público (documentos e
 * apólice), due diligence (lista/decisão), ficha da empresa por perfil
 * (company-profile-access.test.js, access-control.test.js), plano e
 * subscrição (pricing-plans.test.js, subscricoes-em-lote.test.js), série
 * fiscal/data de adesão (credit-note.test.js), dados bancários e limite de
 * orçamento, criação direta de utilizador.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CompanyControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPANY_ADMIN_EMAIL = "admin@petroangola.co.ao";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String FINANCEIRO_EMAIL = "financeiro@petroangola.co.ao";
    private static final String FORNECEDOR_EMAIL = "fornecedor@kianda.co.ao";
    private static final String ADMIN_SISTEMA_EMAIL = "admin@kixima.co.ao";
    private static final byte[] PDF = "%PDF-1.4 documento de teste".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private String login(String email) throws Exception {
        return login(email, PASSWORD);
    }

    private String login(String email, String password) throws Exception {
        var res = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    private String companyIdOf(String taxId) {
        return jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, taxId);
    }

    private MockMultipartHttpServletRequestBuilder registo(Map<String, String> campos, String... docTypes) {
        MockMultipartHttpServletRequestBuilder b = multipart("/api/companies/register");
        for (String doc : docTypes) b.file(new MockMultipartFile(doc, doc.toLowerCase() + ".pdf", "application/pdf", PDF));
        campos.forEach(b::param);
        return b;
    }

    private Map<String, String> camposCliente(String sufixo) {
        Map<String, String> m = new HashMap<>();
        m.put("name", "Nova Operadora " + sufixo);
        m.put("taxId", "AO-NOVA-" + sufixo);
        m.put("type", "CLIENTE");
        m.put("contactEmail", "geral@nova-" + sufixo + ".co.ao");
        m.put("adminName", "Admin Nova");
        m.put("adminEmail", "admin@nova-" + sufixo + ".co.ao");
        m.put("adminPassword", "SenhaForte@2026xyz");
        m.put("employees", "300");
        m.put("termsAccepted", "true");
        return m;
    }

    @Test
    void cadastroPublicoDueDiligenceEDecisao() throws Exception {
        String sufixo = String.valueOf(System.currentTimeMillis());
        // Sem os documentos obrigatórios (CLIENTE: certidão + alvará) é recusado.
        mockMvc.perform(registo(camposCliente(sufixo), "CERTIDAO_COMERCIAL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("Alvará Comercial")));

        // Sem aceitar os termos é um erro de validação.
        Map<String, String> semTermos = camposCliente(sufixo);
        semTermos.put("termsAccepted", "false");
        mockMvc.perform(registo(semTermos, "CERTIDAO_COMERCIAL", "ALVARA_COMERCIAL")).andExpect(status().isUnprocessableEntity());

        // Cadastro completo: PENDENTE; 300 trabalhadores = GRANDE, que exige o PRO.
        var res = mockMvc.perform(registo(camposCliente(sufixo), "CERTIDAO_COMERCIAL", "ALVARA_COMERCIAL"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDENTE"))
                .andExpect(jsonPath("$.size").value("GRANDE"))
                .andExpect(jsonPath("$.plan").value("PRO"))
                .andExpect(jsonPath("$.termsAcceptedAt").isString())
                .andReturn();
        String novaId = objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText();
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM company_documents WHERE company_id = ?", Integer.class, novaId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT role::text FROM users WHERE email = ?", String.class, "admin@nova-" + sufixo + ".co.ao"))
                .isEqualTo("COMPANY_ADMIN");

        // NIF repetido -> 409.
        mockMvc.perform(registo(camposCliente(sufixo), "CERTIDAO_COMERCIAL", "ALVARA_COMERCIAL")).andExpect(status().isConflict());

        // Fornecedora sem apólice -> 400.
        Map<String, String> fornecedora = camposCliente(sufixo + "F");
        fornecedora.put("type", "FORNECEDOR");
        mockMvc.perform(registo(fornecedora, "CERTIDAO_COMERCIAL", "ALVARA_COMERCIAL", "LICENCA_ANPG"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("apólice")));

        // Due diligence: o Admin lista (com filtro e com subscrição só quando pedida) e decide.
        String adminToken = login(ADMIN_SISTEMA_EMAIL);
        mockMvc.perform(get("/api/companies").param("status", "PENDENTE").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + novaId + "')]").exists())
                .andExpect(jsonPath("$[0].subscricao").doesNotExist());
        mockMvc.perform(get("/api/companies").param("comSubscricao", "true").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + novaId + "')].subscricao.activeUsers").value(1))
                .andExpect(jsonPath("$[0].subscricao.monthly.currency").value("USD"));
        for (String valor : new String[]{"1", "sim", "false"}) {
            mockMvc.perform(get("/api/companies").param("comSubscricao", valor).header("Authorization", "Bearer " + adminToken))
                    .andExpect(jsonPath("$[0].subscricao").doesNotExist());
        }
        mockMvc.perform(get("/api/companies").header("Authorization", "Bearer " + login(COMPANY_ADMIN_EMAIL))).andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/companies/" + novaId + "/decision")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("approve", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APROVADA"))
                .andExpect(jsonPath("$.approvedAt").isString());
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'EMPRESA_DECIDIDA' AND entity_id = ?", Integer.class, novaId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE type = 'CADASTRO_EMPRESA_APROVADO' AND company_id = ?", Integer.class, novaId)).isEqualTo(1);

        // Depois de aprovada, o Company Admin criado no cadastro entra e vê a ficha (documentos incluídos).
        String novoAdminToken = login("admin@nova-" + sufixo + ".co.ao", "SenhaForte@2026xyz");
        mockMvc.perform(get("/api/companies/" + novaId).header("Authorization", "Bearer " + novoAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents.length()").value(2))
                .andExpect(jsonPath("$.documents[0].type").value("CERTIDAO_COMERCIAL"));
    }

    @Test
    void aFichaDaEmpresaEDoCompanyAdminEDoAdminDoSistema() throws Exception {
        String companyId = companyIdOf("AO-CLI-0001");
        String outraEmpresaId = companyIdOf("AO-FOR-0001");
        String companyAdmin = login(COMPANY_ADMIN_EMAIL);

        mockMvc.perform(get("/api/companies/" + companyId).header("Authorization", "Bearer " + companyAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taxId").value("AO-CLI-0001"))
                .andExpect(jsonPath("$.clientPolicies").isArray())
                .andExpect(jsonPath("$.supplierPolicies").isArray());
        // Mesma empresa, papéis diferentes: restrição por PERFIL.
        mockMvc.perform(get("/api/companies/" + companyId).header("Authorization", "Bearer " + login(COMPRADOR_EMAIL))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/companies/" + companyId).header("Authorization", "Bearer " + login(FINANCEIRO_EMAIL))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/companies/" + outraEmpresaId).header("Authorization", "Bearer " + login(FORNECEDOR_EMAIL))).andExpect(status().isForbidden());
        // Isolamento entre empresas e acesso global do Admin.
        mockMvc.perform(get("/api/companies/" + outraEmpresaId).header("Authorization", "Bearer " + companyAdmin)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/companies/" + outraEmpresaId).header("Authorization", "Bearer " + login(ADMIN_SISTEMA_EMAIL))).andExpect(status().isOk());

        // Limite de orçamento: só a própria empresa (o Admin do Sistema vê qualquer uma).
        mockMvc.perform(put("/api/companies/" + outraEmpresaId + "/budget-limit")
                        .header("Authorization", "Bearer " + companyAdmin)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("periodMonthly", 1, "currency", "AOA"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/companies/" + companyId + "/budget-limit")
                        .header("Authorization", "Bearer " + companyAdmin)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("periodMonthly", 500000, "currency", "AOA"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodMonthly").value(500000.0));
        mockMvc.perform(get("/api/companies/" + companyId).header("Authorization", "Bearer " + companyAdmin))
                .andExpect(jsonPath("$.budgetLimit.periodMonthly").value(500000.0));
    }

    @Test
    void gestaoDoPlanoESubscricao() throws Exception {
        String companyId = companyIdOf("AO-FOR-0001");
        String adminToken = login(ADMIN_SISTEMA_EMAIL);
        String fornecedorToken = login(FORNECEDOR_EMAIL);

        mockMvc.perform(put("/api/companies/" + companyId + "/plan")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("size", "GRANDE", "plan", "PRO", "seatPriceUsd", 80))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value("GRANDE"))
                .andExpect(jsonPath("$.plan").value("PRO"))
                .andExpect(jsonPath("$.seatPriceUsd").value(80));
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'PLANO_ALTERADO' AND entity_id = ?", Integer.class, companyId)).isEqualTo(1);

        // Uma empresa GRANDE não pode ficar no plano BÁSICO; o teto do preço é validação (422).
        mockMvc.perform(put("/api/companies/" + companyId + "/plan")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("size", "GRANDE", "plan", "BASICO"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("PRO")));
        mockMvc.perform(put("/api/companies/" + companyId + "/plan")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("seatPriceUsd", 250))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(put("/api/companies/" + companyId + "/plan")
                        .header("Authorization", "Bearer " + login(COMPANY_ADMIN_EMAIL))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("plan", "PRO"))))
                .andExpect(status().isForbidden());

        // A empresa consulta a sua subscrição: custo mensal = utilizadores ativos × preço por utilizador.
        var sub = mockMvc.perform(get("/api/companies/" + companyId + "/subscription").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.company.plan").value("PRO"))
                .andExpect(jsonPath("$.monthly.currency").value("USD"))
                .andExpect(jsonPath("$.features.erpIntegration").value(true))
                .andExpect(jsonPath("$.requiredPlan").value("PRO"))
                .andReturn();
        var json = objectMapper.readTree(sub.getResponse().getContentAsString());
        assertThat(json.get("monthly").get("amountUsd").decimalValue())
                // `company.seatPriceUsd` é coluna Decimal → texto (como no Node); `monthly.amountUsd` é aritmética → número.
                .isEqualByComparingTo(new java.math.BigDecimal(json.get("company").get("seatPriceUsd").asText()).multiply(java.math.BigDecimal.valueOf(json.get("activeUsers").asInt())));
        // ... e não a de outra empresa.
        mockMvc.perform(get("/api/companies/" + companyIdOf("AO-CLI-0001") + "/subscription").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void serieFiscalDataDeAdesaoEDadosBancarios() throws Exception {
        String companyId = companyIdOf("AO-FOR-0001");
        String adminToken = login(ADMIN_SISTEMA_EMAIL);
        String fornecedorToken = login(FORNECEDOR_EMAIL);

        // Só o Admin do Sistema declara a série e a data de adesão.
        mockMvc.perform(put("/api/companies/" + companyId + "/serie-fiscal")
                        .header("Authorization", "Bearer " + fornecedorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("serieFiscal", "X"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/companies/" + companyId + "/serie-fiscal")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("serieFiscal", "TESTEHTTP"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serieFiscal").value("TESTEHTTP"));
        mockMvc.perform(put("/api/companies/" + companyId + "/serie-fiscal")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("serieFiscal", "A B!"))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(put("/api/companies/" + companyId + "/data-adesao")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("dataAdesao", "2026-01-01T00:00:00Z"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataAdesaoFacturacaoElectronica").value("2026-01-01T00:00:00Z"));
        mockMvc.perform(put("/api/companies/" + companyId + "/data-adesao")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"dataAdesao\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataAdesaoFacturacaoElectronica").doesNotExist());
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action IN ('SERIE_FISCAL_ALTERADA','DATA_ADESAO_FACTURACAO_ALTERADA') AND entity_id = ?", Integer.class, companyId)).isEqualTo(3);

        // Dados bancários: a própria empresa grava (IBAN normalizado) e a auditoria só guarda os últimos 4 dígitos.
        mockMvc.perform(put("/api/companies/" + companyId + "/bank-details")
                        .header("Authorization", "Bearer " + fornecedorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("bankName", " BAI ", "iban", "ao06 0040 0000 1234 5678 9012 3", "swift", "baiaaoLU"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankName").value("BAI"))
                .andExpect(jsonPath("$.iban").value("AO06004000001234567890123"))
                .andExpect(jsonPath("$.swift").value("BAIAAOLU"));
        mockMvc.perform(get("/api/companies/" + companyId + "/bank-details").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iban").value("AO06004000001234567890123"));
        entityManager.flush();
        String detail = jdbcTemplate.queryForObject("SELECT detail::text FROM audit_logs WHERE action = 'DADOS_BANCARIOS_ALTERADOS' AND entity_id = ?", String.class, companyId);
        assertThat(detail).contains("••••0123").doesNotContain("AO06004000001234567890123");
        mockMvc.perform(get("/api/companies/" + companyIdOf("AO-CLI-0001") + "/bank-details").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void criacaoDiretaDeUtilizador() throws Exception {
        String companyAdmin = login(COMPANY_ADMIN_EMAIL);
        String kiandaId = companyIdOf("AO-FOR-0001");

        mockMvc.perform(post("/api/companies/users")
                        .header("Authorization", "Bearer " + companyAdmin)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "Novo Comprador", "email", "novo.comprador@petroangola.co.ao",
                                "password", "SenhaForte@2026xyz", "role", "COMPRADOR"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("COMPRADOR"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT company_id FROM users WHERE email = 'novo.comprador@petroangola.co.ao'", String.class))
                .isEqualTo(companyIdOf("AO-CLI-0001"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'UTILIZADOR_CRIADO' AND entity_ref = 'novo.comprador@petroangola.co.ao'", Integer.class)).isEqualTo(1);

        // Senha fraca (422), email repetido (409), ADMIN_SISTEMA nunca por aqui (422).
        mockMvc.perform(post("/api/companies/users")
                        .header("Authorization", "Bearer " + companyAdmin)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "Fraco", "email", "fraco@petroangola.co.ao", "password", "123", "role", "COMPRADOR"))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/companies/users")
                        .header("Authorization", "Bearer " + companyAdmin)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "Repetido", "email", COMPRADOR_EMAIL, "password", "SenhaForte@2026xyz", "role", "COMPRADOR"))))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/companies/users")
                        .header("Authorization", "Bearer " + login(ADMIN_SISTEMA_EMAIL))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "Super", "email", "super@kixima.co.ao", "password", "SenhaForte@2026xyz", "role", "ADMIN_SISTEMA"))))
                .andExpect(status().isUnprocessableEntity());

        // O Admin do Sistema cria em qualquer empresa (companyId no corpo).
        mockMvc.perform(post("/api/companies/users")
                        .header("Authorization", "Bearer " + login(ADMIN_SISTEMA_EMAIL))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "Vendedor Kianda", "email", "vendedor2@kianda.co.ao",
                                "password", "SenhaForte@2026xyz", "role", "FORNECEDOR", "companyId", kiandaId))))
                .andExpect(status().isCreated());
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT company_id FROM users WHERE email = 'vendedor2@kianda.co.ao'", String.class)).isEqualTo(kiandaId);
    }
}
