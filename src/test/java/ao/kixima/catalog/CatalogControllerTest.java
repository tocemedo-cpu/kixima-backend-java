package ao.kixima.catalog;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de paridade de contrato (plano, secção 4) para o troço de leitura de
 * catalogController.js/catalogRoutes.js, contra os produtos já semeados na
 * mesma base de teste local do Node. `@Transactional` garante que o
 * incrementView (best-effort) não deixa marca depois do teste.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CatalogControllerTest {

    private static final String EMAIL = "comprador@petroangola.co.ao";
    private static final String PASSWORD = "Kixima@123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private String login() throws Exception {
        var res = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void listarCatalogoExigeSessao() throws Exception {
        mockMvc.perform(get("/api/catalog"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listarCatalogoAutenticadoDevolveProdutosComFornecedor() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/catalog").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").isString())
                .andExpect(jsonPath("$[0].supplier.id").isString())
                .andExpect(jsonPath("$[0].images").doesNotExist()); // listCatalog não inclui media, tal como o Node.
    }

    @Test
    void obterProdutoPorSlugIncluiImagensEDocumentosEFornecedorReduzido() throws Exception {
        String token = login();
        var slugRes = mockMvc.perform(get("/api/catalog").header("Authorization", "Bearer " + token))
                .andReturn();
        String slug = objectMapper.readTree(slugRes.getResponse().getContentAsString()).get(0).get("slug").asText();

        mockMvc.perform(get("/api/catalog/slug/" + slug).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.images").isArray())
                .andExpect(jsonPath("$.documents").isArray())
                .andExpect(jsonPath("$.supplier.id").isString())
                .andExpect(jsonPath("$.supplier.city").doesNotExist()); // getProductBySlug não inclui city, só getBySlug de listCatalog inclui.
    }

    @Test
    void produtoInexistenteDevolve404ComEnvelopeDoNode() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/catalog/id-que-nao-existe-de-todo").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Produto não encontrado."));
    }

    @Test
    void documentacaoDoFornecedorListaDocumentosDeCredenciamentoESoParaFornecedorOuCompanyAdmin() throws Exception {
        String compradorToken = login();
        mockMvc.perform(get("/api/catalog/documents").header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isForbidden());

        var res = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", "fornecedor@kianda.co.ao", "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        String fornecedorToken = objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();

        mockMvc.perform(get("/api/catalog/documents").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productDocs").isArray())
                .andExpect(jsonPath("$.companyDocs").isArray())
                .andExpect(jsonPath("$.companyDocs.length()").value(2))
                .andExpect(jsonPath("$.companyDocs[?(@.type=='CERTIDAO_COMERCIAL')].originalName").value("certidao-kianda.pdf"))
                .andExpect(jsonPath("$.companyDocs[?(@.type=='LICENCA_ANPG')]").exists());
    }

    @Test
    void stockEMovimentosDeInventarioSoParaFornecedorOuCompanyAdminComAvisoNaTransicao() throws Exception {
        String supplierCompanyId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, "AO-FOR-0001");
        String productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE supplier_id = ? AND name = ?", String.class,
                supplierCompanyId, "Mangueira hidráulica de alta pressão 2\"");

        String compradorToken = login();
        var res = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", "fornecedor@kianda.co.ao", "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        String fornecedorToken = objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();

        // Só FORNECEDOR/COMPANY_ADMIN gere stock.
        mockMvc.perform(patch("/api/catalog/" + productId + "/stock")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("stockQuantity", 50))))
                .andExpect(status().isForbidden());

        // Actualiza os campos de inventário — a resposta é o produto "cru" (sem supplier/images/documents).
        mockMvc.perform(patch("/api/catalog/" + productId + "/stock")
                        .header("Authorization", "Bearer " + fornecedorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "stockQuantity", 50, "minStock", 10, "warehouse", "Armazém Central", "availability", "DISPONIVEL"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockQuantity").value(50))
                .andExpect(jsonPath("$.minStock").value(10))
                .andExpect(jsonPath("$.warehouse").value("Armazém Central"))
                .andExpect(jsonPath("$.supplier").doesNotExist())
                .andExpect(jsonPath("$.images").doesNotExist());
        entityManager.flush();
        entityManager.clear();

        // Quantidade inválida é rejeitada.
        mockMvc.perform(post("/api/catalog/movements")
                        .header("Authorization", "Bearer " + fornecedorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("productId", productId, "type", "SAIDA", "quantity", 0))))
                .andExpect(status().isUnprocessableEntity());

        // Saída de 45 — o stock (50) cruza o mínimo (10): 50-45=5.
        mockMvc.perform(post("/api/catalog/movements")
                        .header("Authorization", "Bearer " + fornecedorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("productId", productId, "type", "SAIDA", "quantity", 45, "note", "Ajuste de inventário"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("SAIDA"))
                .andExpect(jsonPath("$.quantity").value(45));
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/catalog/" + productId).header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockQuantity").value(5));

        // Histórico — o mais recente primeiro, com o nome do produto aninhado.
        var listaRes = mockMvc.perform(get("/api/catalog/movements").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].type").value("SAIDA"))
                .andExpect(jsonPath("$.itens[0].product.name").value("Mangueira hidráulica de alta pressão 2\""))
                .andReturn();
        JsonNode lista = objectMapper.readTree(listaRes.getResponse().getContentAsString());
        assertEquals(1, lista.get("total").asInt());

        // Filtrado por ENTRADA — nenhum resultado (só houve uma SAIDA).
        mockMvc.perform(get("/api/catalog/movements?type=ENTRADA").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }
}
