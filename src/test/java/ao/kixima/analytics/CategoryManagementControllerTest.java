package ao.kixima.analytics;

import ao.kixima.audit.Actor;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.discount.DiscountThresholdService;
import ao.kixima.discount.dto.CreateDiscountThresholdRequest;
import ao.kixima.discount.dto.DiscountThresholdDto;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Espelha tests/category-management.test.js — agregações, patamares, IA que se recusa a fingir, e as rotas do lado da empresa (Lacunas D.7). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CategoryManagementControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPANY_ADMIN_EMAIL = "admin@petroangola.co.ao";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String FINANCEIRO_EMAIL = "financeiro@petroangola.co.ao";
    private static final String FORNECEDOR_EMAIL = "fornecedor@kianda.co.ao";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CategoryAnalyticsService categoryAnalyticsService;

    @Autowired
    private DiscountThresholdService discountThresholdService;

    @Autowired
    private AiRecommendationService aiRecommendationService;

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

    private String compradoraId() {
        return jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = 'AO-CLI-0001'", String.class);
    }

    private void porNoPlano(String plano, int rank) {
        entityManager.flush();
        jdbcTemplate.update("UPDATE companies SET plan = ?::\"CompanyPlan\", search_rank = ? WHERE id = ?", plano, rank, compradoraId());
        entityManager.clear();
    }

    @Test
    void agregacoesBatemComOsDadosReais() {
        String companyId = compradoraId();
        Map<String, Object> r = categoryAnalyticsService.volumePorCategoria(companyId, null);
        @SuppressWarnings("unchecked") List<Map<String, Object>> categorias = (List<Map<String, Object>>) r.get("categorias");
        double soma = categorias.stream().mapToDouble(c -> (double) c.get("valor")).sum();
        assertThat(Math.round(soma * 100)).isEqualTo(Math.round((double) r.get("total") * 100));
        for (Map<String, Object> c : categorias) assertThat((double) c.get("percentual")).isBetween(0d, 100.01);

        // Sem histórico devolve zero, nunca lança.
        CategoryAnalyticsService.MediaMensal semHistorico = categoryAnalyticsService.mediaMensalPorProduto(companyId, "inexistente");
        assertThat(semHistorico.mediaMensal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(semHistorico.amostras()).isEqualTo(0);
        Map<String, Object> previsao = categoryAnalyticsService.previsaoNecessidade(companyId, "inexistente", 3);
        assertThat(previsao.get("previsaoProximoMes")).isEqualTo(0d);
        assertThat(previsao.get("baseMeses")).isEqualTo(0);

        // Sem thresholds ativos, não há oportunidade nenhuma; só entram categorias com pelo menos 3 POs.
        assertThat(categoryAnalyticsService.oportunidadesConsolidacao(companyId, null, List.of())).isEmpty();
        for (Map<String, Object> o : categoryAnalyticsService.oportunidadesConsolidacao(companyId, null,
                List.of(new CategoryAnalyticsService.Patamar(1, 2.5, true)))) {
            assertThat((int) o.get("numeroPos")).isGreaterThanOrEqualTo(3);
            assertThat((double) o.get("faltamUsd")).isGreaterThan(0);
        }
    }

    @Test
    void patamaresConfiguraveisEmRuntime() {
        // O seed da migração tem os 3 patamares: 100k/2,5% · 500k/5% · 1M/10%.
        List<DiscountThresholdDto> lista = discountThresholdService.listar(true);
        Map<Integer, Double> porValor = new java.util.HashMap<>();
        for (DiscountThresholdDto t : lista) porValor.put(t.minVolumeUsd().intValue(), t.discountPercent().doubleValue());
        assertThat(porValor.get(100000)).isEqualTo(2.5);
        assertThat(porValor.get(500000)).isEqualTo(5d);
        assertThat(porValor.get(1000000)).isEqualTo(10d);

        Map<String, Object> abaixo = discountThresholdService.proximoThreshold(50000, null);
        assertThat(abaixo.get("descontoAtual")).isEqualTo(0d);
        assertThat(((Map<?, ?>) abaixo.get("proximoThreshold")).get("minVolumeUsd")).isEqualTo(100000d);
        assertThat(abaixo.get("faltamUsd")).isEqualTo(50000d);
        Map<String, Object> entre = discountThresholdService.proximoThreshold(600000, null);
        assertThat(entre.get("descontoAtual")).isEqualTo(5d);
        assertThat(((Map<?, ?>) entre.get("proximoThreshold")).get("minVolumeUsd")).isEqualTo(1000000d);
        Map<String, Object> acima = discountThresholdService.proximoThreshold(5000000, null);
        assertThat(acima.get("descontoAtual")).isEqualTo(10d);
        assertThat(acima.get("proximoThreshold")).isNull();
        assertThat(acima.get("faltamUsd")).isNull();

        // Mudar a tabela muda o resultado.
        assertThat(discountThresholdService.proximoThreshold(150000, null).get("descontoAtual")).isEqualTo(2.5);
        Actor anonimo = Actor.anonimo(null);
        DiscountThresholdDto novo = discountThresholdService.criar(new CreateDiscountThresholdRequest(BigDecimal.valueOf(120000), BigDecimal.valueOf(3), null), anonimo);
        assertThat(discountThresholdService.proximoThreshold(150000, null).get("descontoAtual")).isEqualTo(3d);
        discountThresholdService.remover(novo.id(), anonimo);

        // Validação e inexistente.
        assertThatThrownBy(() -> discountThresholdService.criar(new CreateDiscountThresholdRequest(BigDecimal.TEN, BigDecimal.valueOf(150), null), anonimo)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> discountThresholdService.criar(new CreateDiscountThresholdRequest(BigDecimal.valueOf(-5), BigDecimal.TEN, null), anonimo)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> discountThresholdService.remover("00000000-0000-0000-0000-000000000000", anonimo)).isInstanceOf(NotFoundException.class);

        // A IA recusa-se a fingir: sem ANTHROPIC_API_KEY, texto null com motivo explícito, nunca lança.
        assertThat(aiRecommendationService.disponivel()).isFalse();
        Map<String, Object> rec = aiRecommendationService.gerar("Teste", 0, List.of(), discountThresholdService.proximoThreshold(0, null), List.of());
        assertThat(rec.get("texto")).isNull();
        assertThat((String) rec.get("motivo")).contains("ANTHROPIC_API_KEY");
    }

    @Test
    void rotasDoLadoDaEmpresa() throws Exception {
        mockMvc.perform(get("/api/category-management/analise")).andExpect(status().isUnauthorized());

        porNoPlano("PRO", 2);
        mockMvc.perform(get("/api/category-management/analise").header("Authorization", "Bearer " + login(FORNECEDOR_EMAIL))).andExpect(status().isForbidden());

        // Plano CORE é bloqueado, e a mensagem diz que é PRO.
        porNoPlano("CORE", 1);
        String companyAdmin = login(COMPANY_ADMIN_EMAIL);
        mockMvc.perform(get("/api/category-management/analise").header("Authorization", "Bearer " + companyAdmin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PLANO_INSUFICIENTE"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("PRO")));

        // No plano PRO, análise completa, sem recomendação de IA (não configurada); recomendacao=0 salta a chamada.
        porNoPlano("PRO", 2);
        mockMvc.perform(get("/api/category-management/analise").header("Authorization", "Bearer " + companyAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.volumeAtualUsd").value(org.hamcrest.Matchers.greaterThanOrEqualTo(0d)))
                .andExpect(jsonPath("$.categorias").isArray())
                .andExpect(jsonPath("$.oportunidadesConsolidacao").isArray())
                .andExpect(jsonPath("$.recomendacao.texto").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.recomendacao.motivo").value(org.hamcrest.Matchers.containsString("ANTHROPIC_API_KEY")));
        mockMvc.perform(get("/api/category-management/analise?recomendacao=0").header("Authorization", "Bearer " + companyAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recomendacao.texto").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.recomendacao.motivo").value("Não pedida."));

        // Financeiro e comprador também têm acesso; média mensal por produto.
        mockMvc.perform(get("/api/category-management/analise").header("Authorization", "Bearer " + login(FINANCEIRO_EMAIL))).andExpect(status().isOk());
        mockMvc.perform(get("/api/category-management/analise").header("Authorization", "Bearer " + login(COMPRADOR_EMAIL))).andExpect(status().isOk());
        mockMvc.perform(get("/api/category-management/produtos/inexistente/media-mensal").header("Authorization", "Bearer " + companyAdmin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mediaMensal").value(0)).andExpect(jsonPath("$.amostras").value(0));
    }
}
