package ao.kixima.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de paridade de contrato para policyRoutes.js/policyController.js: as
 * duas apólices (Fornecedor→KIXIMA submetida pelo próprio, KIXIMA→Cliente
 * emitida pelo Admin do Sistema) e a guarda de acesso de listForCompany —
 * ninguém lê apólices de outra empresa a não ser o ADMIN_SISTEMA.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PolicyControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String ADMIN_SISTEMA_EMAIL = "admin@kixima.co.ao";
    private static final String COMPANY_ADMIN_EMAIL = "admin@petroangola.co.ao";
    private static final String FORNECEDOR_EMAIL = "fornecedor@kianda.co.ao";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String login(String email) throws Exception {
        var res = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void apoliceFornecedorSoOFornecedorSubmeteEOAdminDoSistemaDecide() throws Exception {
        String fornecedorToken = login(FORNECEDOR_EMAIL);
        String companyAdminToken = login(COMPANY_ADMIN_EMAIL);
        String adminToken = login(ADMIN_SISTEMA_EMAIL);

        Map<String, Object> body = Map.of(
                "policyNumber", "SUP-001",
                "insurer", "Seguradora Angolana",
                "coverageAmount", 3000000,
                "currency", "AOA",
                "validFrom", "2026-01-01T00:00:00Z",
                "validUntil", "2027-01-01T00:00:00Z");

        // Empresa CLIENTE (petroangola) não pode submeter a apólice Fornecedor→KIXIMA.
        mockMvc.perform(post("/api/policies/supplier-to-kixima")
                        .header("Authorization", "Bearer " + companyAdminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());

        var criarRes = mockMvc.perform(post("/api/policies/supplier-to-kixima")
                        .header("Authorization", "Bearer " + fornecedorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUBMETIDA"))
                .andExpect(jsonPath("$.policyNumber").value("SUP-001"))
                .andReturn();
        String policyId = objectMapper.readTree(criarRes.getResponse().getContentAsString()).get("id").asText();

        // Só ADMIN_SISTEMA decide.
        mockMvc.perform(patch("/api/policies/supplier-to-kixima/" + policyId + "/decision")
                        .header("Authorization", "Bearer " + fornecedorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("approve", true))))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/policies/supplier-to-kixima/" + policyId + "/decision")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("approve", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APROVADA"));
    }

    @Test
    void apoliceClienteSoAdminDoSistemaEmiteEListagemDaEmpresaEGuardada() throws Exception {
        String adminToken = login(ADMIN_SISTEMA_EMAIL);
        String companyAdminToken = login(COMPANY_ADMIN_EMAIL);
        String fornecedorToken = login(FORNECEDOR_EMAIL);

        String petroangolaId = jdbcTemplate.queryForObject(
                "SELECT id FROM companies WHERE tax_id = ?", String.class, "AO-CLI-0001");
        String kiandaId = jdbcTemplate.queryForObject(
                "SELECT id FROM companies WHERE tax_id = ?", String.class, "AO-FOR-0001");

        Map<String, Object> body = Map.of(
                "policyNumber", "CLI-001",
                "insurer", "Seguradora Angolana",
                "coverageAmount", 8000000,
                "currency", "AOA",
                "validFrom", "2026-01-01T00:00:00Z",
                "validUntil", "2027-01-01T00:00:00Z");

        // Só ADMIN_SISTEMA emite apólices KIXIMA→Cliente.
        mockMvc.perform(post("/api/policies/kixima-to-client/" + petroangolaId)
                        .header("Authorization", "Bearer " + companyAdminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/policies/kixima-to-client/" + petroangolaId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APROVADA"))
                .andExpect(jsonPath("$.companyId").value(petroangolaId));

        // O próprio Company Admin lê as apólices da sua empresa.
        var listaRes = mockMvc.perform(get("/api/policies/company")
                        .header("Authorization", "Bearer " + companyAdminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode lista = objectMapper.readTree(listaRes.getResponse().getContentAsString());
        boolean encontrada = false;
        for (JsonNode item : lista.get("kiximaToClient")) {
            if ("CLI-001".equals(item.get("policyNumber").asText())) encontrada = true;
        }
        assertThat(encontrada).isTrue();
        assertThat(lista.get("supplierToKixima")).isEmpty();

        // Não pode ler as apólices de OUTRA empresa.
        mockMvc.perform(get("/api/policies/company/" + kiandaId)
                        .header("Authorization", "Bearer " + companyAdminToken))
                .andExpect(status().isForbidden());

        // ADMIN_SISTEMA lê a de qualquer empresa.
        mockMvc.perform(get("/api/policies/company/" + petroangolaId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kiximaToClient[?(@.policyNumber=='CLI-001')]").exists());

        // Fornecedor de outra empresa também não pode ler a de petroangola.
        mockMvc.perform(get("/api/policies/company/" + petroangolaId)
                        .header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isForbidden());
    }
}
