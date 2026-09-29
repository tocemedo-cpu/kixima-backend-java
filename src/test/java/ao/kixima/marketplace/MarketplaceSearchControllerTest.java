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
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Espelha a secção "pesquisa e paginação" de tests/marketplace.test.js
 * (search/facets/compare/suppliers — Lacunas D.3) e tests/kits.test.js (D.4).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MarketplaceSearchControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String FORNECEDOR_EMAIL = "fornecedor@kianda.co.ao";

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

    private JsonNode obter(String token, String url) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String companyIdDe(String email) {
        return jdbcTemplate.queryForObject("SELECT company_id FROM users WHERE email = ?", String.class, email);
    }

    @Test
    void pesquisaPaginacaoFiltrosOrdenacaoEFacetas() throws Exception {
        String comprador = login(COMPRADOR_EMAIL);

        // Paginação devolve estrutura {items,total,page,pages,limit}.
        JsonNode pag = obter(comprador, "/api/marketplace/search?limit=2&page=1");
        assertThat(pag.has("total")).isTrue();
        assertThat(pag.has("pages")).isTrue();
        assertThat(pag.get("limit").asInt()).isEqualTo(2);
        assertThat(pag.get("items").size()).isLessThanOrEqualTo(2);
        assertThat(pag.get("items").get(0).has("isFavorite")).isTrue();
        assertThat(pag.get("items").get(0).get("supplier").has("destaque")).isTrue();
        assertThat(pag.get("items").get(0).get("supplier").has("plan")).isFalse();

        // Filtro kind=SERVICO devolve apenas serviços.
        JsonNode servicos = obter(comprador, "/api/marketplace/search?kind=SERVICO&limit=48");
        assertThat(servicos.get("items").size()).isGreaterThan(0);
        for (JsonNode p : servicos.get("items")) assertThat(p.get("kind").asText()).isEqualTo("SERVICO");
        String favProductId = servicos.get("items").get(0).get("id").asText();

        // Pesquisa por texto (q) filtra por nome/descrição — sem acentos, via search_text.
        JsonNode texto = obter(comprador, "/api/marketplace/search?q=ultrassom");
        boolean encontrou = false;
        for (JsonNode p : texto.get("items")) if (p.get("name").asText().toLowerCase().contains("ultrassom")) encontrou = true;
        assertThat(encontrou).isTrue();

        // Ordenação por menor preço.
        JsonNode ordenado = obter(comprador, "/api/marketplace/search?sort=preco_asc&limit=48");
        List<Double> precos = new ArrayList<>();
        for (JsonNode p : ordenado.get("items")) precos.add(p.get("unitPrice").asDouble());
        assertThat(precos).isSorted();

        // Parâmetros inválidos → 422.
        mockMvc.perform(get("/api/marketplace/search?kind=OUTRO").header("Authorization", "Bearer " + comprador))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/marketplace/search?limit=99").header("Authorization", "Bearer " + comprador))
                .andExpect(status().isUnprocessableEntity());

        // Facetas devolvem contagem por categoria; tipos, localizações e certificações.
        JsonNode facetasServico = obter(comprador, "/api/marketplace/facets?kind=SERVICO");
        assertThat(facetasServico.get("categories").isArray()).isTrue();
        assertThat(facetasServico.get("categories").get(0).has("count")).isTrue();
        JsonNode facetas = obter(comprador, "/api/marketplace/facets");
        assertThat(facetas.get("kinds").isArray()).isTrue();
        boolean temTipo = false;
        for (JsonNode k : facetas.get("kinds")) if (List.of("SERVICO", "PRODUTO").contains(k.get("name").asText())) temTipo = true;
        assertThat(temTipo).isTrue();
        assertThat(facetas.get("kinds").get(0).has("count")).isTrue();
        for (JsonNode c : facetas.get("countries")) assertThat(c.get("name").asText()).isNotBlank();
        assertThat(facetas.get("certifications").isArray()).isTrue();
        assertThat(facetas.get("priceBounds").get("max").asDouble()).isGreaterThan(0);

        // Comprador não vê produtos da própria empresa.
        String compradorCompanyId = companyIdDe(COMPRADOR_EMAIL);
        for (JsonNode p : obter(comprador, "/api/marketplace/search?limit=48").get("items")) {
            assertThat(p.get("supplierId").asText()).isNotEqualTo(compradorCompanyId);
        }

        // Fornecedores verificados e comparação de fornecedores.
        JsonNode fornecedores = obter(comprador, "/api/marketplace/suppliers");
        assertThat(fornecedores.isArray()).isTrue();
        for (JsonNode f : fornecedores) assertThat(f.has("productCount")).isTrue();
        JsonNode comparacao = obter(comprador, "/api/marketplace/compare?productId=" + favProductId);
        assertThat(comparacao.get("base").get("name").asText()).isNotBlank();
        assertThat(comparacao.get("count").asInt()).isEqualTo(comparacao.get("offers").size());
        assertThat(comparacao.get("count").asInt()).isGreaterThanOrEqualTo(1); // o próprio produto (é de outra empresa)
        assertThat(comparacao.get("offers").get(0).has("effectivePrice")).isTrue();
        mockMvc.perform(get("/api/marketplace/compare").header("Authorization", "Bearer " + comprador))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/marketplace/compare?productId=" + favProductId).header("Authorization", "Bearer " + login(FORNECEDOR_EMAIL)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/marketplace/compare?productId=inexistente").header("Authorization", "Bearer " + comprador))
                .andExpect(status().isNotFound());
    }

    // --- kits.test.js ------------------------------------------------------------

    private String criarProduto(String supplierId, String nome, int preco) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO products (id, supplier_id, name, category, unit_price, certifications, tags, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'Materiais', ?, '{}'::text[], '{}'::text[], now(), now())", id, supplierId, nome, preco);
        entityManager.clear();
        return id;
    }

    @Test
    void kitsPacotesDeProdutosDaPropriaEmpresa() throws Exception {
        String fornecedor = login(FORNECEDOR_EMAIL);
        String supplierId = companyIdDe(FORNECEDOR_EMAIL);
        String p1 = criarProduto(supplierId, "Kit Item A", 100);
        String p2 = criarProduto(supplierId, "Kit Item B", 250);

        // Cria um kit com produtos da própria empresa.
        var criado = mockMvc.perform(post("/api/kits").header("Authorization", "Bearer " + fornecedor).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "Kit de Manutenção", "description", "Pacote básico",
                                "items", List.of(Map.of("productId", p1, "quantity", 2), Map.of("productId", p2, "quantity", 1))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].product.name").isString())
                .andReturn();
        String kitId = objectMapper.readTree(criado.getResponse().getContentAsString()).get("id").asText();

        // Lista os kits da empresa.
        JsonNode lista = obter(fornecedor, "/api/kits");
        boolean temKit = false;
        for (JsonNode k : lista) if ("Kit de Manutenção".equals(k.get("name").asText())) temKit = true;
        assertThat(temKit).isTrue();

        // Rejeita produto de outra empresa (400); corpo inválido (422); comprador não cria (403).
        List<String> alheios = jdbcTemplate.queryForList("SELECT id FROM products WHERE supplier_id <> ? LIMIT 1", String.class, supplierId);
        String alheio = alheios.isEmpty() ? null : alheios.get(0);
        if (alheio != null) {
            mockMvc.perform(post("/api/kits").header("Authorization", "Bearer " + fornecedor).contentType("application/json")
                            .content(objectMapper.writeValueAsString(Map.of("name", "Kit Inválido", "items", List.of(Map.of("productId", alheio, "quantity", 1))))))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/kits").header("Authorization", "Bearer " + fornecedor).contentType("application/json")
                        .content("{\"name\":\"K\",\"items\":[]}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/kits").header("Authorization", "Bearer " + login(COMPRADOR_EMAIL)).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", "Kit Intruso", "items", List.of(Map.of("productId", p1, "quantity", 1))))))
                .andExpect(status().isForbidden());

        // Criar e remover um kit fica no trilho de auditoria.
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT entity_ref FROM audit_logs WHERE action = 'CATALOGO_KIT_CRIADO' AND entity_id = ?", String.class, kitId))
                .isEqualTo("Kit de Manutenção");
        mockMvc.perform(delete("/api/kits/" + kitId).header("Authorization", "Bearer " + fornecedor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(kitId));
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'CATALOGO_KIT_REMOVIDO' AND entity_id = ?", Integer.class, kitId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT active FROM kits WHERE id = ?", Boolean.class, kitId)).isFalse();
        assertThat(obter(fornecedor, "/api/kits").size()).isEqualTo(lista.size() - 1);
        mockMvc.perform(delete("/api/kits/" + kitId).header("Authorization", "Bearer " + login(COMPRADOR_EMAIL))).andExpect(status().isForbidden());
    }
}
