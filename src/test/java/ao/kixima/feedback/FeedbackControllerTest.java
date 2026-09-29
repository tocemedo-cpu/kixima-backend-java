package ao.kixima.feedback;

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
 * Teste de paridade de contrato para feedbackService.js/feedbackRoutes.js +
 * o troço de moderação em adminRoutes.js e a parede pública em
 * publicRoutes.js: submissão verificada contra o histórico real da
 * empresa (nunca um id qualquer), aprovação pelo Admin do Sistema, e a
 * categoria PAGAMENTO a recusar-se explicitamente (503, domínio Payment
 * ainda não portado).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FeedbackControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String ADMIN_SISTEMA_EMAIL = "admin@kixima.co.ao";
    private static final String SUPPLIER_TAX_ID = "AO-FOR-0001";

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

    @Test
    void submissaoVerificadaModeracaoEParedePublica() throws Exception {
        String supplierCompanyId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, SUPPLIER_TAX_ID);
        String compradorToken = login(COMPRADOR_EMAIL);
        String adminToken = login(ADMIN_SISTEMA_EMAIL);

        // Opções reais — o fornecedor (Kianda) aparece porque há POs reais entre as duas empresas.
        var opcoesRes = mockMvc.perform(get("/api/feedback/opcoes").header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.FORNECEDOR[?(@.id=='" + supplierCompanyId + "')]").exists())
                // Os pagamentos PROCESSADOS das POs da empresa (seed: PAY-2026-0000x) aparecem como "PAY-… — PO-…".
                .andExpect(jsonPath("$.PAGAMENTO").isArray())
                .andExpect(jsonPath("$.PAGAMENTO[0].label").value(org.hamcrest.Matchers.containsString("PAY-")))
                .andReturn();
        JsonNode opcoes = objectMapper.readTree(opcoesRes.getResponse().getContentAsString());
        String produtoId = opcoes.get("PRODUTO").get(0).get("id").asText();
        String pedidoId = opcoes.get("PEDIDO").get(0).get("id").asText();
        String atendimentoId = opcoes.get("ATENDIMENTO").get(0).get("id").asText();

        // Um id que não pertence ao histórico da empresa é rejeitado (nunca aceite às cegas).
        mockMvc.perform(post("/api/feedback")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "categoria", "FORNECEDOR", "targetId", "id-que-nao-existe-de-todo",
                                "rating", 5, "message", "Excelente parceiro."))))
                .andExpect(status().isUnprocessableEntity());

        // PAGAMENTO: um id que não é um pagamento processado de uma PO da empresa é rejeitado; um real é aceite e verificado.
        mockMvc.perform(post("/api/feedback")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "categoria", "PAGAMENTO", "targetId", "qualquer", "rating", 5, "message", "Pago a tempo."))))
                .andExpect(status().isUnprocessableEntity());
        String pagamentoId = opcoes.get("PAGAMENTO").get(0).get("id").asText();
        mockMvc.perform(post("/api/feedback")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "categoria", "PAGAMENTO", "targetId", pagamentoId, "rating", 5, "message", "Pago a tempo."))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recebido").value(true))
                .andExpect(jsonPath("$.id").isString());

        // Avaliação real, verificada — sobre o fornecedor.
        var criarRes = mockMvc.perform(post("/api/feedback")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "categoria", "FORNECEDOR", "targetId", supplierCompanyId,
                                "rating", 5, "message", "Entrega sempre dentro do prazo."))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recebido").value(true))
                .andReturn();
        String feedbackId = objectMapper.readTree(criarRes.getResponse().getContentAsString()).get("id").asText();

        // Produto, pedido e atendimento também passam pela mesma verificação.
        mockMvc.perform(post("/api/feedback")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "categoria", "PRODUTO", "targetId", produtoId, "rating", 4, "message", "Boa qualidade."))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/feedback")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "categoria", "PEDIDO", "targetId", pedidoId, "rating", 5, "message", "Processo tranquilo."))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/feedback")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "categoria", "ATENDIMENTO", "targetId", atendimentoId, "rating", 3, "message", "Resposta um pouco lenta."))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/feedback")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "categoria", "EXPERIENCIA_GERAL", "rating", 5, "message", "Ótima experiência no geral."))))
                .andExpect(status().isCreated());
        entityManager.flush();
        entityManager.clear();

        // "As minhas" — todas as que este utilizador enviou, qualquer estado.
        var minhasRes = mockMvc.perform(get("/api/feedback/minhas").header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(6, objectMapper.readTree(minhasRes.getResponse().getContentAsString()).size()); // 5 + a de PAGAMENTO

        // Ainda não aprovadas — a parede pública está vazia.
        mockMvc.perform(get("/api/public/feedback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.feedback").isArray())
                .andExpect(jsonPath("$.feedback.length()").value(0));

        // A moderação exige ADMIN_SISTEMA.
        mockMvc.perform(get("/api/admin/feedback").header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isForbidden());
        var filaRes = mockMvc.perform(get("/api/admin/feedback?status=pendente").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode fila = objectMapper.readTree(filaRes.getResponse().getContentAsString());
        assertEquals(6, fila.get("itens").size());
        assertEquals(true, fila.get("itens").get(0).has("approved"));

        // Aprova só uma.
        mockMvc.perform(patch("/api/admin/feedback/" + feedbackId + "/aprovar").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true));
        entityManager.flush();
        entityManager.clear();

        // Agora aparece na parede pública — sem o campo `approved` (é detalhe de moderação).
        mockMvc.perform(get("/api/public/feedback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.average").value(5.0))
                .andExpect(jsonPath("$.feedback[0].approved").doesNotExist())
                .andExpect(jsonPath("$.feedback[0].user.name").isString())
                .andExpect(jsonPath("$.feedback[0].company.name").isString());

        // Remover uma avaliação.
        mockMvc.perform(get("/api/admin/feedback").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(6));
    }
}
