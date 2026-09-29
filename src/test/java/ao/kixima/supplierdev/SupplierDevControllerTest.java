package ao.kixima.supplierdev;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de paridade de contrato para supplierDevService.js/
 * supplierDevRoutes.js: candidatura pública (com/sem sessão), consulta
 * pública por referência, gestão pelo Admin do Sistema (listar/atualizar) e
 * aprovação (cria a Company + a apólice Fornecedor→KIXIMA + o convite de
 * fundação — ver InviteService.criarConviteDeFundacao, finalmente com um
 * chamador real).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SupplierDevControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String ADMIN_SISTEMA_EMAIL = "admin@kixima.co.ao";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    /**
     * `validate(supplierDevSchema)` — tests/pricing-plans.test.js "a candidatura
     * é validada" e "não se submete sem aceitar a taxa", mais todos os limites
     * do schema (max por campo, employees inteiro não negativo, track do enum),
     * com os textos por omissão do zod e os campos todos reportados de uma vez.
     */
    @Test
    void aCandidaturaEValidadaComOsTextosDoZod() throws Exception {
        // { companyName: 'X', contactName: 'Y', contactEmail: 'nao-e-email', feeAccepted: true } → 422
        mockMvc.perform(post("/api/supplier-development/requests")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "companyName", "X", "contactName", "Y", "contactEmail", "nao-e-email", "feeAccepted", true))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message").value("Dados inválidos."))
                .andExpect(jsonPath("$.error.details.formErrors").isEmpty())
                .andExpect(jsonPath("$.error.details.fieldErrors.companyName[0]").value("Indique o nome da empresa."))
                .andExpect(jsonPath("$.error.details.fieldErrors.contactName[0]").value("Indique o nome do contacto."))
                .andExpect(jsonPath("$.error.details.fieldErrors.contactEmail[0]").value("Indique um email válido."))
                .andExpect(jsonPath("$.error.details.fieldErrors.feeAccepted").doesNotExist());

        // Sem aceitar a taxa (ausente) — só esse campo falha, com a frase do errorMap do schema.
        mockMvc.perform(post("/api/supplier-development/requests")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "companyName", "Sem Aceite, Lda", "contactName", "Ana", "contactEmail", "ana@semaceite.co.ao"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.error.details.fieldErrors.feeAccepted[0]").value("Confirme que aceita a taxa de acesso cobrada na submissão."));
        // z.literal(true): "true" em texto e false também não servem.
        mockMvc.perform(post("/api/supplier-development/requests")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "companyName", "Sem Aceite, Lda", "contactName", "Ana", "contactEmail", "ana@semaceite.co.ao", "feeAccepted", "true"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.fieldErrors.feeAccepted[0]").value("Confirme que aceita a taxa de acesso cobrada na submissão."));

        // Campos obrigatórios ausentes → "Required" (não as mensagens de min(2)).
        mockMvc.perform(post("/api/supplier-development/requests")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.fieldErrors.companyName[0]").value("Required"))
                .andExpect(jsonPath("$.error.details.fieldErrors.contactName[0]").value("Required"))
                .andExpect(jsonPath("$.error.details.fieldErrors.contactEmail[0]").value("Required"))
                .andExpect(jsonPath("$.error.details.fieldErrors.feeAccepted[0]").value("Confirme que aceita a taxa de acesso cobrada na submissão."));

        // Limites dos opcionais: taxId ≤ 40, contactPhone ≤ 40, province ≤ 60, sector ≤ 120, needs ≤ 2000,
        // employees inteiro ≥ 0, track no enum.
        Map<String, Object> demais = new java.util.LinkedHashMap<>();
        demais.put("companyName", "Metalúrgica do Kwanza, Lda");
        demais.put("contactName", "Joana Silva");
        demais.put("contactEmail", "joana@metalkwanza.co.ao");
        demais.put("feeAccepted", true);
        demais.put("taxId", "9".repeat(41));
        demais.put("contactPhone", "9".repeat(41));
        demais.put("province", "L".repeat(61));
        demais.put("sector", "M".repeat(121));
        demais.put("needs", "n".repeat(2001));
        demais.put("employees", -1);
        demais.put("track", "OUTRO");
        mockMvc.perform(post("/api/supplier-development/requests")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(demais)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.fieldErrors.taxId[0]").value("String must contain at most 40 character(s)"))
                .andExpect(jsonPath("$.error.details.fieldErrors.contactPhone[0]").value("String must contain at most 40 character(s)"))
                .andExpect(jsonPath("$.error.details.fieldErrors.province[0]").value("String must contain at most 60 character(s)"))
                .andExpect(jsonPath("$.error.details.fieldErrors.sector[0]").value("String must contain at most 120 character(s)"))
                .andExpect(jsonPath("$.error.details.fieldErrors.needs[0]").value("String must contain at most 2000 character(s)"))
                .andExpect(jsonPath("$.error.details.fieldErrors.employees[0]").value("Number must be greater than or equal to 0"))
                .andExpect(jsonPath("$.error.details.fieldErrors.track[0]")
                        .value("Invalid enum value. Expected 'BUROCRACIA' | 'PARCERIA' | 'AMBOS', received 'OUTRO'"))
                .andExpect(jsonPath("$.error.details.fieldErrors.companyName").doesNotExist());

        // z.coerce.number().int(): 1.5 é float; "abc" é NaN — o zod acumula as verificações de número.
        demais.put("employees", 1.5);
        mockMvc.perform(post("/api/supplier-development/requests").contentType("application/json").content(objectMapper.writeValueAsString(demais)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.fieldErrors.employees[0]").value("Expected integer, received float"))
                .andExpect(jsonPath("$.error.details.fieldErrors.employees.length()").value(1));
        demais.put("employees", -1.5);
        mockMvc.perform(post("/api/supplier-development/requests").contentType("application/json").content(objectMapper.writeValueAsString(demais)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.fieldErrors.employees[0]").value("Expected integer, received float"))
                .andExpect(jsonPath("$.error.details.fieldErrors.employees[1]").value("Number must be greater than or equal to 0"));
        demais.put("employees", "abc");
        mockMvc.perform(post("/api/supplier-development/requests").contentType("application/json").content(objectMapper.writeValueAsString(demais)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.fieldErrors.employees[0]").value("Expected number, received nan"));

        // Tudo no limite (e "24" coagido para número) passa — 201.
        Map<String, Object> valido = new java.util.LinkedHashMap<>(demais);
        valido.put("taxId", "9".repeat(40));
        valido.put("contactPhone", "9".repeat(40));
        valido.put("province", "L".repeat(60));
        valido.put("sector", "M".repeat(120));
        valido.put("needs", "n".repeat(2000));
        valido.put("employees", "24");
        valido.put("track", "PARCERIA");
        mockMvc.perform(post("/api/supplier-development/requests").contentType("application/json").content(objectMapper.writeValueAsString(valido)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECEBIDA"));
    }

    @Test
    void candidaturaPublicaAcompanhamentoEGestaoPeloAdminDoSistema() throws Exception {
        // Taxa de acesso — pública, antes de qualquer candidatura.
        mockMvc.perform(get("/api/supplier-development/fee"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.dueOnSubmission").value(true));

        // Sem aceitar a taxa, a candidatura é rejeitada.
        mockMvc.perform(post("/api/supplier-development/requests")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "companyName", "Nova Fornecedora, Lda", "contactName", "Joana Silva",
                                "contactEmail", "joana@novafornecedora.co.ao"))))
                .andExpect(status().isUnprocessableEntity());

        // Candidatura pública, sem sessão.
        var criarRes = mockMvc.perform(post("/api/supplier-development/requests")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "companyName", "Nova Fornecedora, Lda", "contactName", "Joana Silva",
                                "contactEmail", "joana@novafornecedora.co.ao", "track", "BUROCRACIA", "feeAccepted", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECEBIDA"))
                .andExpect(jsonPath("$.reference").value(org.hamcrest.Matchers.matchesPattern("^SD-\\d{4}-\\d{6}$")))
                .andExpect(jsonPath("$.accessFee.currency").value("USD"))
                .andExpect(jsonPath("$.accessFee.status").value("PENDENTE"))
                .andReturn();
        String reference = objectMapper.readTree(criarRes.getResponse().getContentAsString()).get("reference").asText();
        entityManager.flush();
        entityManager.clear();

        // Acompanhamento público por referência.
        mockMvc.perform(get("/api/supplier-development/requests/" + reference + "/track"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyName").value("Nova Fornecedora, Lda"))
                .andExpect(jsonPath("$.track").value("BUROCRACIA"))
                .andExpect(jsonPath("$.status").value("RECEBIDA"));

        // A gestão exige sessão de Admin do Sistema.
        mockMvc.perform(get("/api/supplier-development/requests")).andExpect(status().isUnauthorized());
        String compradorToken = login(COMPRADOR_EMAIL);
        mockMvc.perform(get("/api/supplier-development/requests").header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isForbidden());

        String adminToken = login(ADMIN_SISTEMA_EMAIL);
        var listaRes = mockMvc.perform(get("/api/supplier-development/requests").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.reference=='" + reference + "')]").exists())
                .andReturn();
        JsonNode lista = objectMapper.readTree(listaRes.getResponse().getContentAsString());
        assertEquals(1, lista.get("kpis").get("total").asInt());
        assertEquals(1, lista.get("kpis").get("recebidas").asInt());
        String id = null;
        for (JsonNode item : lista.get("items")) {
            if (reference.equals(item.get("reference").asText())) id = item.get("id").asText();
        }
        assertNotNull(id);

        // Atualizar estado/notas.
        mockMvc.perform(patch("/api/supplier-development/requests/" + id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("status", "EM_ANALISE", "adminNotes", "A validar documentação."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EM_ANALISE"))
                .andExpect(jsonPath("$.adminNotes").value("A validar documentação."));
        entityManager.flush();
        entityManager.clear();

        // Aprovar sem NIF (a candidatura não trouxe um) — rejeitado.
        mockMvc.perform(patch("/api/supplier-development/requests/" + id + "/approve")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("policy", Map.of(
                                "policyNumber", "APL-2026-001", "insurer", "Seguradora Nacional",
                                "coverageAmount", 5000000, "validFrom", "2026-01-01T00:00:00Z", "validUntil", "2027-01-01T00:00:00Z")))))
                .andExpect(status().isUnprocessableEntity());

        // Aprovação completa — cria a Company (FORNECEDOR/PENDENTE) + a apólice + o convite de fundação.
        mockMvc.perform(patch("/api/supplier-development/requests/" + id + "/approve")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "taxId", "AO-SD-90001",
                                "policy", Map.of("policyNumber", "APL-2026-001", "insurer", "Seguradora Nacional",
                                        "coverageAmount", 5000000, "validFrom", "2026-01-01T00:00:00Z", "validUntil", "2027-01-01T00:00:00Z")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONCLUIDA"))
                .andExpect(jsonPath("$.companyId").isString())
                .andReturn();
        entityManager.flush();
        entityManager.clear();

        String companyId = jdbcTemplate.queryForObject("SELECT company_id FROM supplier_dev_requests WHERE id = ?", String.class, id);
        Map<String, Object> empresa = jdbcTemplate.queryForMap(
                "SELECT type, status, name FROM companies WHERE id = ?", companyId);
        assertEquals("FORNECEDOR", empresa.get("type"));
        assertEquals("PENDENTE", empresa.get("status"));
        assertEquals("Nova Fornecedora, Lda", empresa.get("name"));

        Long apolices = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM supplier_to_kixima_policies WHERE company_id = ?", Long.class, companyId);
        assertEquals(1L, apolices);

        Map<String, Object> convite = jdbcTemplate.queryForMap(
                "SELECT role, status, email FROM employee_invites WHERE company_id = ?", companyId);
        assertEquals("COMPANY_ADMIN", convite.get("role"));
        assertEquals("PENDENTE", convite.get("status"));
        assertEquals("joana@novafornecedora.co.ao", convite.get("email"));

        // Uma segunda aprovação já não é possível.
        mockMvc.perform(patch("/api/supplier-development/requests/" + id + "/approve")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("policy", Map.of(
                                "policyNumber", "X", "insurer", "Y", "coverageAmount", 1,
                                "validFrom", "2026-01-01T00:00:00Z", "validUntil", "2027-01-01T00:00:00Z")))))
                .andExpect(status().isBadRequest());
    }
}
