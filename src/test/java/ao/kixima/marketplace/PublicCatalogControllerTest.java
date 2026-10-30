package ao.kixima.marketplace;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Montra pública do catálogo: sem sessão, só leitura, lista branca de campos, só fornecedoras aprovadas e produtos ativos. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PublicCatalogControllerTest {

    /** Campos que NUNCA podem sair na montra pública. */
    private static final Set<String> PROIBIDOS = Set.of("sku", "manufacturerCode", "supplierNotes", "stockQuantity", "warehouse", "minStock",
            "viewCount", "active", "updatedAt", "isFavorite", "unspscCode");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @PersistenceContext
    private EntityManager entityManager;

    private JsonNode obter(String url) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void pesquisaSemSessaoDevolveProdutosSemCamposInternos() throws Exception {
        JsonNode r = obter("/api/public/catalog/search?limit=48");
        assertThat(r.get("total").asInt()).isGreaterThan(0);
        for (JsonNode item : r.get("items")) {
            item.fieldNames().forEachRemaining(campo -> assertThat(PUBLICOS_OK).as("campo %s não pode ser público", campo).contains(campo));
            for (String proibido : PROIBIDOS) assertThat(item.has(proibido)).as(proibido).isFalse();
            assertThat(item.get("supplier").has("searchRank")).isFalse();
            assertThat(item.get("supplier").has("destaque")).isFalse();
            assertThat(item.get("slug").asText()).isNotBlank();
        }
    }

    private static final Set<String> PUBLICOS_OK = PublicCatalogController.CAMPOS_PRODUTO;

    @Test
    void filtrosFacetasEOrdenacaoFuncionamSemSessao() throws Exception {
        JsonNode facetas = obter("/api/public/catalog/facets");
        assertThat(facetas.get("categories").size()).isGreaterThan(0);
        String categoria = facetas.get("categories").get(0).get("name").asText();
        JsonNode porCategoria = objectMapper.readTree(mockMvc.perform(get("/api/public/catalog/search").param("category", categoria))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(porCategoria.get("total").asInt()).isEqualTo(facetas.get("categories").get(0).get("count").asInt());
        for (JsonNode i : porCategoria.get("items")) assertThat(i.get("category").asText()).isEqualTo(categoria);
        assertThat(obter("/api/public/catalog/search?kind=SERVICO&sort=recentes").get("items").size()).isGreaterThanOrEqualTo(0);
        mockMvc.perform(get("/api/public/catalog/search?sort=inventado")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/public/catalog/search?limit=999")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void fichaDoProdutoPorSlugEAvaliacoesAnonimizadas() throws Exception {
        String slug = obter("/api/public/catalog/search?limit=1").get("items").get(0).get("slug").asText();
        JsonNode p = obter("/api/public/catalog/products/" + slug);
        assertThat(p.get("slug").asText()).isEqualTo(slug);
        assertThat(p.has("images")).isTrue();
        for (String proibido : PROIBIDOS) assertThat(p.has(proibido)).as(proibido).isFalse();
        for (JsonNode r : obter("/api/public/catalog/products/" + slug + "/reviews")) {
            assertThat(r.has("userId")).isFalse();
            assertThat(r.get("authorName").asText()).doesNotContainPattern("\\w{2,}\\s+\\w{2,}$");
        }
        mockMvc.perform(get("/api/public/catalog/products/nao-existe")).andExpect(status().isNotFound());
    }

    @Test
    void produtoRetiradoOuDeFornecedoraNaoAprovadaNaoApareceNemSePedidoPeloSlug() throws Exception {
        JsonNode primeiro = obter("/api/public/catalog/search?limit=1").get("items").get(0);
        String slug = primeiro.get("slug").asText();
        String supplierId = primeiro.get("supplierId").asText();
        int antes = obter("/api/public/catalog/search?limit=48").get("total").asInt();

        jdbcTemplate.update("UPDATE companies SET status = 'PENDENTE'::\"CompanyStatus\" WHERE id = ?", supplierId);
        entityManager.clear(); // o teste corre numa só transação: sem isto o JPA devolveria a fornecedora em cache
        mockMvc.perform(get("/api/public/catalog/products/" + slug)).andExpect(status().isNotFound());
        assertThat(obter("/api/public/catalog/search?limit=48").get("total").asInt()).isLessThan(antes);
        assertThat(obter("/api/public/catalog/suppliers").findValuesAsText("id")).doesNotContain(supplierId);

        jdbcTemplate.update("UPDATE companies SET status = 'APROVADA'::\"CompanyStatus\" WHERE id = ?", supplierId);
        jdbcTemplate.update("UPDATE products SET active = false WHERE slug = ?", slug);
        entityManager.clear();
        mockMvc.perform(get("/api/public/catalog/products/" + slug)).andExpect(status().isNotFound());
    }

    @Test
    void fornecedorasVerificadasTemLimiteValidado() throws Exception {
        JsonNode lista = obter("/api/public/catalog/suppliers?limit=3");
        assertThat(lista.size()).isLessThanOrEqualTo(3);
        for (JsonNode f : lista) assertThat(f.has("name")).isTrue();
        mockMvc.perform(get("/api/public/catalog/suppliers?limit=0")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/public/catalog/suppliers?limit=500")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void ConsultaPublicaNaoAbreAsOperacoesProtegidas() throws Exception {
        for (String url : List.of("/api/marketplace/search", "/api/marketplace/facets", "/api/marketplace/suppliers", "/api/catalog", "/api/quotes", "/api/purchase-orders"))
            mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
        // só GET é público: escrever na montra exige sessão
        mockMvc.perform(post("/api/public/catalog/search").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void respostasPodemSerGuardadasEmCacheCurto() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/public/catalog/search")).andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("public, max-age=30");
    }

    @Test
    void autorDaAvaliacaoApareceAbreviado() {
        assertThat(PublicCatalogController.autorAbreviado("Ana Maria Comprador")).isEqualTo("Ana C.");
        assertThat(PublicCatalogController.autorAbreviado("Ana")).isEqualTo("Ana");
        assertThat(PublicCatalogController.autorAbreviado("  ")).isEqualTo("Cliente");
        assertThat(PublicCatalogController.autorAbreviado(null)).isEqualTo("Cliente");
    }
}
