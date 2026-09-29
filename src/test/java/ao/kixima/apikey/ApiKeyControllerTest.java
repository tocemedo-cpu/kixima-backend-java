package ao.kixima.apikey;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de paridade de contrato para apiKeyService.js + o troço `/api-keys`
 * de catalogRoutes.js: só FORNECEDOR/COMPANY_ADMIN gere, é um recurso do
 * plano PRO, a chave inteira só sai uma vez na criação (nunca na
 * listagem), e {@link ApiKeyService#autenticar} — usado pela futura API
 * pública de catálogo, ainda sem controller — já verifica corretamente
 * segredo, revogação e o gate de plano.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ApiKeyControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String FORNECEDOR_EMAIL = "fornecedor@kianda.co.ao";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String SUPPLIER_TAX_ID = "AO-FOR-0001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApiKeyService apiKeyService;

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

    @Test
    void cicloDeVidaDaChaveDeApiComGuardaDePlanoENuncaExpoeOSegredoNaListagem() throws Exception {
        String supplierCompanyId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, SUPPLIER_TAX_ID);
        String fornecedorToken = login(FORNECEDOR_EMAIL);
        String compradorToken = login(COMPRADOR_EMAIL);

        // Só FORNECEDOR/COMPANY_ADMIN gere as chaves da própria empresa.
        mockMvc.perform(get("/api/catalog/api-keys").header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isForbidden());

        // A empresa está no plano CORE — a API de catálogo é um recurso do plano PRO.
        mockMvc.perform(post("/api/catalog/api-keys")
                        .header("Authorization", "Bearer " + fornecedorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("nome", "ERP da produção"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PLANO_INSUFICIENTE"));

        jdbcTemplate.update("UPDATE companies SET plan = 'PRO' WHERE id = ?", supplierCompanyId);
        entityManager.flush();
        entityManager.clear();

        // Sem nome, é rejeitada.
        mockMvc.perform(post("/api/catalog/api-keys")
                        .header("Authorization", "Bearer " + fornecedorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("nome", "  "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"));

        var criarRes = mockMvc.perform(post("/api/catalog/api-keys")
                        .header("Authorization", "Bearer " + fornecedorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("nome", "ERP da produção"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nome").value("ERP da produção"))
                .andExpect(jsonPath("$.prefixo").isString())
                .andExpect(jsonPath("$.chave").isString())
                .andExpect(jsonPath("$.aviso").isString())
                .andReturn();
        JsonNode criada = objectMapper.readTree(criarRes.getResponse().getContentAsString());
        String id = criada.get("id").asText();
        String prefixo = criada.get("prefixo").asText();
        String chave = criada.get("chave").asText();
        assertThat(chave).startsWith(prefixo + ".");
        entityManager.flush();
        entityManager.clear();

        // A listagem NUNCA devolve a chave inteira, só o prefixo.
        mockMvc.perform(get("/api/catalog/api-keys").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + id + "')].prefixo").value(prefixo))
                .andExpect(jsonPath("$[?(@.id=='" + id + "')].ativa").value(true))
                .andExpect(jsonPath("$[0].chave").doesNotExist());

        // apiKeyService.autenticar — pronto para a futura API pública, já testável hoje.
        ApiKeyService.Sessao sessao = apiKeyService.autenticar(chave);
        assertThat(sessao).isNotNull();
        assertThat(sessao.empresa().getId()).isEqualTo(supplierCompanyId);
        assertThat(sessao.chaveId()).isEqualTo(id);
        assertThat(apiKeyService.autenticar(prefixo + ".segredo-errado")).isNull();
        assertThat(apiKeyService.autenticar("chave-completamente-invalida")).isNull();

        // Revogar — não apaga, só marca.
        mockMvc.perform(delete("/api/catalog/api-keys/" + id).header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.revogadaEm").isString());
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/catalog/api-keys").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + id + "')].ativa").value(false));

        // Uma chave revogada deixa de autenticar.
        assertThat(apiKeyService.autenticar(chave)).isNull();

        // Revogar de novo é idempotente (devolve a mesma data, não lança).
        mockMvc.perform(delete("/api/catalog/api-keys/" + id).header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void naoDeixaPassarDeCincoChavesAtivas() throws Exception {
        String supplierCompanyId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, SUPPLIER_TAX_ID);
        jdbcTemplate.update("UPDATE companies SET plan = 'PRO' WHERE id = ?", supplierCompanyId);
        entityManager.flush();
        entityManager.clear();
        String fornecedorToken = login(FORNECEDOR_EMAIL);

        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(post("/api/catalog/api-keys")
                            .header("Authorization", "Bearer " + fornecedorToken)
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(Map.of("nome", "Chave " + i))))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(post("/api/catalog/api-keys")
                        .header("Authorization", "Bearer " + fornecedorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("nome", "Sexta chave"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"));
    }
}
