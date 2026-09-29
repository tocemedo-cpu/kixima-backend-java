package ao.kixima.conversation;

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

import org.springframework.mock.web.MockMultipartFile;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de paridade de contrato para conversationService.js/
 * conversationRoutes.js + riskAlertService.js/riskAnalysisService.js: uma
 * conversa "product" entre comprador e fornecedor, uma mensagem inócua
 * (sem alerta), uma mensagem com sinais de risco combinados (gera
 * RiskAlert MEDIUM+), e o painel de Trust & Safety do Suporte a aceder só
 * às conversas sinalizadas — nunca a uma sem alerta.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ConversationControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String FORNECEDOR_EMAIL = "fornecedor@kianda.co.ao";
    private static final String FINANCEIRO_EMAIL = "financeiro@petroangola.co.ao";
    private static final String ADMIN_SISTEMA_EMAIL = "admin@kixima.co.ao";
    private static final String SUPPLIER_TAX_ID = "AO-FOR-0001";
    private static final String PRODUCT_NAME = "Mangueira hidráulica de alta pressão 2\"";

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
    void conversaComContextoProdutoMensagemDeRiscoEPainelDeTrustSafety() throws Exception {
        String supplierCompanyId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, SUPPLIER_TAX_ID);
        String productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE supplier_id = ? AND name = ?", String.class, supplierCompanyId, PRODUCT_NAME);

        String compradorToken = login(COMPRADOR_EMAIL);
        String fornecedorToken = login(FORNECEDOR_EMAIL);
        String financeiroToken = login(FINANCEIRO_EMAIL);
        String adminToken = login(ADMIN_SISTEMA_EMAIL);

        // 1. Comprador inicia a conversa a partir do produto.
        var startRes = mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("contextType", "product", "contextId", productId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supplierCompanyId").value(supplierCompanyId))
                .andReturn();
        String conversationId = objectMapper.readTree(startRes.getResponse().getContentAsString()).get("id").asText();
        entityManager.flush();
        entityManager.clear();

        // Reaproveita a conversa já aberta em vez de duplicar.
        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("contextType", "product", "contextId", productId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(conversationId));

        // 2. Aparece na listagem de ambos os lados, com o contra-lado correto.
        var listaCompradorRes = mockMvc.perform(get("/api/conversations").header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode listaComprador = objectMapper.readTree(listaCompradorRes.getResponse().getContentAsString());
        assertEquals(1, listaComprador.size());
        assertEquals("Fornecedora Industrial Kianda, Lda", listaComprador.get(0).get("counterpart").get("name").asText());

        // Mesmo o Admin do Sistema, por omissão, NÃO é participante — 404 (a exceção é a via de alerta, mais abaixo).
        mockMvc.perform(get("/api/conversations/" + conversationId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        // Um utilizador da MESMA empresa (mas que não iniciou a conversa) já é participante — isolamento é por empresa, não por pessoa.
        mockMvc.perform(get("/api/conversations/" + conversationId).header("Authorization", "Bearer " + financeiroToken))
                .andExpect(status().isOk());

        // Acima de 10MB o multer aborta com LIMIT_FILE_SIZE, que o errorHandler.js
        // traduz em 413 com esta frase. O fileFilter (tipo) corre ANTES do limite,
        // por isso um ficheiro grande do tipo errado continua a ser 422.
        byte[] enorme = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy("%PDF-1.4\n".getBytes(StandardCharsets.UTF_8), 0, enorme, 0, 9);
        mockMvc.perform(multipart("/api/conversations/" + conversationId + "/messages")
                        .file(new MockMultipartFile("attachment", "enorme.pdf", "application/pdf", enorme))
                        .param("body", "Segue o anexo.")
                        .header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.error.code").value("LIMIT_FILE_SIZE"))
                .andExpect(jsonPath("$.error.message").value("O ficheiro é demasiado grande. Reduza o tamanho da imagem e tente novamente."));
        mockMvc.perform(multipart("/api/conversations/" + conversationId + "/messages")
                        .file(new MockMultipartFile("attachment", "enorme.txt", "text/plain", enorme))
                        .param("body", "Segue o anexo.")
                        .header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value("Documento inválido — use PDF ou imagem (PNG/JPG)."));

        // 3. Mensagem inócua — sem alerta.
        mockMvc.perform(post("/api/conversations/" + conversationId + "/messages")
                        .param("body", "Bom dia, qual o prazo de entrega para Luanda?")
                        .header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isCreated());
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/conversations/admin/alerts").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.conversationId=='" + conversationId + "')]").doesNotExist());

        // Sem alerta nenhum, mesmo o Admin do Sistema não acede à conversa por esta via.
        mockMvc.perform(get("/api/conversations/admin/conversations/" + conversationId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        // 4. Mensagem com sinais de risco combinados (pagamento_fora, peso 4) -> MEDIUM.
        var riscoRes = mockMvc.perform(post("/api/conversations/" + conversationId + "/messages")
                        .param("body", "Podemos combinar o pagamento direto, assim evitamos a taxa da Kixima.")
                        .header("Authorization", "Bearer " + fornecedorToken))
                .andExpect(status().isCreated())
                .andReturn();
        entityManager.flush();
        entityManager.clear();

        // 5. O alerta aparece no painel do Suporte, com as empresas identificadas.
        var alertasRes = mockMvc.perform(get("/api/conversations/admin/alerts").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode alertas = objectMapper.readTree(alertasRes.getResponse().getContentAsString());
        JsonNode alertaDaConversa = null;
        for (JsonNode a : alertas) if (conversationId.equals(a.get("conversationId").asText())) alertaDaConversa = a;
        assertTrue(alertaDaConversa != null, "Devia ter sido criado um alerta MEDIUM+ para esta conversa.");
        assertEquals("ABERTO", alertaDaConversa.get("status").asText());
        assertTrue(java.util.List.of("MEDIUM", "HIGH", "CRITICAL").contains(alertaDaConversa.get("level").asText()));
        assertEquals("Fornecedora Industrial Kianda, Lda", alertaDaConversa.get("conversation").get("supplierCompany").asText());
        String alertId = alertaDaConversa.get("id").asText();

        // 6. Agora sim, o Admin acede à conversa sinalizada — com as 2 mensagens.
        var detalheRes = mockMvc.perform(get("/api/conversations/admin/conversations/" + conversationId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode detalhe = objectMapper.readTree(detalheRes.getResponse().getContentAsString());
        assertEquals(2, detalhe.get("messages").size());
        assertEquals(1, detalhe.get("alerts").size());

        // Um utilizador comum (não Suporte) não tem esta rota, mesmo sendo participante.
        mockMvc.perform(get("/api/conversations/admin/conversations/" + conversationId).header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isForbidden());

        // 7. Reclassificar o alerta como falso positivo.
        mockMvc.perform(patch("/api/conversations/admin/alerts/" + alertId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("status", "FALSO_POSITIVO", "decision", "Cliente habitual, falso positivo confirmado."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FALSO_POSITIVO"))
                .andExpect(jsonPath("$.reviewedById").isString());

        // 8. Contador de não lidas do comprador reflecte a mensagem do fornecedor.
        mockMvc.perform(get("/api/conversations/unread-count").header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
        mockMvc.perform(post("/api/conversations/" + conversationId + "/read").header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/conversations/unread-count").header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void contextoDeContratoOuCotacaoInexistenteDa404() throws Exception {
        String token = login(COMPRADOR_EMAIL);
        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("contextType", "contract", "contextId", "qualquer-id"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("contextType", "quote", "contextId", "qualquer-id"))))
                .andExpect(status().isNotFound());
    }
}
