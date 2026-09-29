package ao.kixima.quote;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Espelha tests/quotes.test.js: comprador pede -> fornecedor responde -> comprador encerra. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class QuoteControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
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
    void fluxoDeCotacaoAbertaRespondidaFechada() throws Exception {
        String compradorToken = login(COMPRADOR_EMAIL);
        String fornecedorToken = login(FORNECEDOR_EMAIL);
        String buyerCompanyId = jdbcTemplate.queryForObject("SELECT company_id FROM users WHERE email = ?", String.class, COMPRADOR_EMAIL);
        String supplierCompanyId = jdbcTemplate.queryForObject("SELECT company_id FROM users WHERE email = ?", String.class, FORNECEDOR_EMAIL);
        String productId = jdbcTemplate.queryForObject("SELECT id FROM products WHERE supplier_id = ? ORDER BY name LIMIT 1", String.class, supplierCompanyId);

        // comprador cria um pedido de cotação (ABERTA)
        var criado = mockMvc.perform(post("/api/quotes")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("supplierCompanyId", supplierCompanyId, "note", "Preciso com urgência",
                                "items", List.of(Map.of("productId", productId, "quantity", 3))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ABERTA"))
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andExpect(jsonPath("$.items[0].product.id").value(productId))
                .andExpect(jsonPath("$.buyerCompany.id").value(buyerCompanyId))
                .andExpect(jsonPath("$.supplierCompany.id").value(supplierCompanyId))
                .andReturn();
        String quoteId = objectMapper.readTree(criado.getResponse().getContentAsString()).get("id").asText();

        // fornecedor vê a solicitação e responde (RESPONDIDA)
        mockMvc.perform(get("/api/quotes").param("status", "ABERTA").header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + quoteId + "')]").exists());
        mockMvc.perform(patch("/api/quotes/" + quoteId + "/respond")
                        .header("Authorization", "Bearer " + fornecedorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("price", 45000, "leadDays", 10, "note", "Inclui transporte"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESPONDIDA"))
                .andExpect(jsonPath("$.responsePrice").value(45000))
                .andExpect(jsonPath("$.responseLeadDays").value(10));

        // O comprador não pode responder; um comprador de outra empresa não encerra.
        mockMvc.perform(patch("/api/quotes/" + quoteId + "/respond")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("price", 1))))
                .andExpect(status().isForbidden());

        // comprador vê a resposta e encerra (FECHADA)
        mockMvc.perform(get("/api/quotes").header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + quoteId + "')].status").value("RESPONDIDA"));
        mockMvc.perform(patch("/api/quotes/" + quoteId + "/close").header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FECHADA"));

        // Depois de encerrado, o fornecedor já não responde (400).
        mockMvc.perform(patch("/api/quotes/" + quoteId + "/respond")
                        .header("Authorization", "Bearer " + fornecedorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("price", 1))))
                .andExpect(status().isBadRequest());

        // O Chat Comercial resolve o contexto "quote" contra a cotação.
        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("contextType", "quote", "contextId", quoteId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supplierCompanyId").value(supplierCompanyId));
    }

    @Test
    void naoSePodePedirCotacaoAPropriaEmpresaNemComProdutosDeOutroFornecedor() throws Exception {
        String compradorToken = login(COMPRADOR_EMAIL);
        String buyerCompanyId = jdbcTemplate.queryForObject("SELECT company_id FROM users WHERE email = ?", String.class, COMPRADOR_EMAIL);
        String supplierCompanyId = jdbcTemplate.queryForObject("SELECT company_id FROM users WHERE email = ?", String.class, FORNECEDOR_EMAIL);
        String productId = jdbcTemplate.queryForObject("SELECT id FROM products WHERE supplier_id = ? ORDER BY name LIMIT 1", String.class, supplierCompanyId);

        mockMvc.perform(post("/api/quotes")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("supplierCompanyId", buyerCompanyId,
                                "items", List.of(Map.of("productId", productId, "quantity", 1))))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/quotes")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("supplierCompanyId", supplierCompanyId,
                                "items", List.of(Map.of("productId", "inexistente", "quantity", 1))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("Todos os produtos devem pertencer ao fornecedor escolhido."));

        // Validação: sem itens é 422.
        mockMvc.perform(post("/api/quotes")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("supplierCompanyId", supplierCompanyId, "items", List.of()))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void umFornecedorNaoPodeCriarPedidosDeCotacao() throws Exception {
        String fornecedorToken = login(FORNECEDOR_EMAIL);
        String supplierCompanyId = jdbcTemplate.queryForObject("SELECT company_id FROM users WHERE email = ?", String.class, FORNECEDOR_EMAIL);
        String productId = jdbcTemplate.queryForObject("SELECT id FROM products WHERE supplier_id = ? ORDER BY name LIMIT 1", String.class, supplierCompanyId);
        mockMvc.perform(post("/api/quotes")
                        .header("Authorization", "Bearer " + fornecedorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("supplierCompanyId", supplierCompanyId,
                                "items", List.of(Map.of("productId", productId, "quantity", 1))))))
                .andExpect(status().isForbidden());
    }
}
