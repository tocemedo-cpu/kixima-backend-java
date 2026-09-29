package ao.kixima.report;

import ao.kixima.company.CompanyPlan;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Espelha tests/reports.test.js, o bloco "Janela de histórico" de planos-degraus.test.js e tests/conteudo-local.test.js. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReportsControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPANY_ADMIN_EMAIL = "admin@petroangola.co.ao";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String FORNECEDOR_EMAIL = "fornecedor@kianda.co.ao";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReportsService reportsService;

    @Autowired
    private ConteudoLocalService conteudoLocalService;

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

    private JsonNode getJson(String path, String token) throws Exception {
        var res = mockMvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    @Test
    void janelaDeHistoricoPorPlano() {
        assertThat(reportsService.janelaDoPlano(CompanyPlan.BASE, null).meses()).isEqualTo(3);
        assertThat(reportsService.janelaDoPlano(CompanyPlan.CORE, null).meses()).isEqualTo(12);
        assertThat(reportsService.janelaDoPlano(CompanyPlan.PRO, null).ilimitado()).isTrue();
        assertThat(reportsService.janelaDoPlano(CompanyPlan.PRO, null).meses()).isNull();
        // Pedir MENOS é aceite; pedir MAIS é cortado e o corte é declarado.
        ReportsService.Janela menos = reportsService.janelaDoPlano(CompanyPlan.CORE, "2");
        assertThat(menos.meses()).isEqualTo(2);
        assertThat(menos.truncada()).isFalse();
        ReportsService.Janela mais = reportsService.janelaDoPlano(CompanyPlan.BASE, "24");
        assertThat(mais.meses()).isEqualTo(3);
        assertThat(mais.truncada()).isTrue();
        assertThat(mais.limitePlano()).isEqualTo(3);
        // Um pedido absurdo cai na janela do plano.
        for (String lixo : new String[]{"abc", "-4", "0", "", null}) {
            assertThat(reportsService.janelaDoPlano(CompanyPlan.BASE, lixo).meses()).isEqualTo(3);
        }
    }

    @Test
    void estatisticasDoFornecedorComJanelaDeclarada() throws Exception {
        String supplierCompanyId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = 'AO-FOR-0001'", String.class);
        jdbcTemplate.update("UPDATE companies SET plan = 'BASE' WHERE id = ?", supplierCompanyId);
        entityManager.clear();
        String fornecedorToken = login(FORNECEDOR_EMAIL);

        JsonNode stats = getJson("/api/reports/fornecedor", fornecedorToken);
        assertThat(stats.get("totalProducts").asInt()).isGreaterThan(0);
        assertThat(stats.has("revenue")).isTrue();
        assertThat(stats.has("statusCounts")).isTrue();
        assertThat(stats.get("topProducts").isArray()).isTrue();
        assertThat(stats.get("topViewed").isArray()).isTrue();
        assertThat(stats.has("totalViews")).isTrue();
        assertThat(stats.get("janela").get("meses").asInt()).isEqualTo(3);
        assertThat(stats.get("janela").get("desde").asText()).isNotBlank();
        assertThat(stats.get("janela").get("semJanela").toString()).contains("totalViews").contains("totalProducts");

        JsonNode truncada = getJson("/api/reports/fornecedor?meses=24", fornecedorToken);
        assertThat(truncada.get("janela").get("truncada").asBoolean()).isTrue();

        mockMvc.perform(get("/api/reports/fornecedor").header("Authorization", "Bearer " + login(COMPRADOR_EMAIL))).andExpect(status().isForbidden());
    }

    @Test
    void relatorioDeConteudoLocalDefensavel() throws Exception {
        String compradoraId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = 'AO-CLI-0001'", String.class);
        // Precondição garantida: nenhum produto com país de origem declarado.
        jdbcTemplate.update("UPDATE products SET country_of_origin = NULL");
        entityManager.clear();

        // Só entram ordens com compromisso real; o critério vai escrito; o dia de fim conta por inteiro.
        Map<String, Object> r = conteudoLocalService.gerar(compradoraId, "2020-01-01", "2035-12-31");
        assertThat(((Map<?, ?>) r.get("totais")).get("ordens")).isEqualTo(6);
        assertThat(((Map<?, ?>) r.get("criterio")).get("estadosIncluidos").toString()).contains("PAGA").doesNotContain("AGUARDANDO_APROVACAO");
        assertThat(((Map<?, ?>) r.get("criterio")).get("baseDeCalculo").toString()).containsIgnoringCase("sem IVA");
        assertThat(((Map<?, ?>) r.get("periodo")).get("ate").toString()).contains("T23:59:59");
        Map<String, Object> vazio = conteudoLocalService.gerar(compradoraId, "1999-01-01", "1999-12-31");
        assertThat(((Map<?, ?>) vazio.get("totais")).get("ordens")).isEqualTo(0);
        assertThat(((Map<?, ?>) vazio.get("contratacaoNacional")).get("percentagem")).isEqualTo(0.0);
        assertThatThrownBy(() -> conteudoLocalService.gerar(compradoraId, "ontem", "hoje")).hasMessageContaining("AAAA-MM-DD");
        assertThatThrownBy(() -> conteudoLocalService.gerar(compradoraId, "2030-01-01", "2020-01-01")).hasMessageContaining("posterior");

        // O desconhecido não conta a favor de quem reporta.
        Map<?, ?> origem = (Map<?, ?>) r.get("origemDoBem");
        assertThat((Double) ((Map<?, ?>) r.get("contratacaoNacional")).get("percentagem")).isGreaterThan(0);
        assertThat(origem.get("percentagemAngolana")).isEqualTo(0.0);
        assertThat(((java.math.BigDecimal) origem.get("porDeclarar")).signum()).isPositive();
        Map<?, ?> qualidade = (Map<?, ?>) r.get("qualidadeDosDados");
        assertThat(qualidade.get("confiavel")).isEqualTo(false);
        assertThat(qualidade.get("aviso").toString()).containsIgnoringCase("não deve ser entregue");
        assertThat((Double) qualidade.get("percentagemSemOrigem")).isGreaterThan(10);

        // Declarar a origem move o número — e só então.
        jdbcTemplate.update("UPDATE products SET country_of_origin = 'Angola'");
        entityManager.clear();
        Map<String, Object> depois = conteudoLocalService.gerar(compradoraId, "2020-01-01", "2035-12-31");
        Map<?, ?> origemDepois = (Map<?, ?>) depois.get("origemDoBem");
        assertThat((Double) origemDepois.get("percentagemAngolana")).isGreaterThan((Double) origem.get("percentagemAngolana"));
        assertThat(((Map<?, ?>) depois.get("qualidadeDosDados")).get("confiavel")).isEqualTo(true);
        assertThat(((Map<?, ?>) depois.get("qualidadeDosDados")).get("aviso")).isNull();

        // Acesso: é do plano Pro, e a mensagem diz qual; sem sessão 401; cada empresa vê só as suas compras.
        jdbcTemplate.update("UPDATE companies SET plan = 'CORE', search_rank = 1 WHERE id = ?", compradoraId);
        entityManager.clear();
        String companyAdminToken = login(COMPANY_ADMIN_EMAIL);
        mockMvc.perform(get("/api/reports/conteudo-local").header("Authorization", "Bearer " + companyAdminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("Relatório de conteúdo local")))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("plano PRO")));
        jdbcTemplate.update("UPDATE companies SET plan = 'PRO', search_rank = 2 WHERE id = ?", compradoraId);
        entityManager.clear();
        JsonNode http = getJson("/api/reports/conteudo-local?de=2020-01-01&ate=2035-12-31", companyAdminToken);
        assertThat(http.has("contratacaoNacional")).isTrue();
        assertThat(http.get("anexo").size()).isGreaterThan(0);
        for (JsonNode a : http.get("anexo")) {
            assertThat(jdbcTemplate.queryForObject("SELECT buyer_company_id FROM purchase_orders WHERE reference = ?", String.class, a.get("referencia").asText()))
                    .isEqualTo(compradoraId);
        }
        mockMvc.perform(get("/api/reports/conteudo-local")).andExpect(status().isUnauthorized());
    }
}
