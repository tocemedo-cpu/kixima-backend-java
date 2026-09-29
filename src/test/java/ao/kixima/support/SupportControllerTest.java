package ao.kixima.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.charset.StandardCharsets;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de paridade de contrato para o troço de tickets/chat de
 * supportRoutes.js/supportChatService.js: criação → listagem própria →
 * listagem admin (enriquecida) → assumir → mensagem com anexo → acesso ao
 * anexo (dono vs. estranho) → resposta do cliente → marcar lidas →
 * resolver/reabrir.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SupportControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String FINANCEIRO_EMAIL = "financeiro@petroangola.co.ao";
    private static final String ADMIN_SISTEMA_EMAIL = "admin@kixima.co.ao";

    // Assinatura PNG real (8 bytes) + preenchimento — suficiente para passar FileSignature.verificar.
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
    };

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String login(String email) throws Exception {
        var res = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void fluxoCompletoDeTicketEChatComAnexo() throws Exception {
        String compradorToken = login(COMPRADOR_EMAIL);
        String financeiroToken = login(FINANCEIRO_EMAIL);
        String adminToken = login(ADMIN_SISTEMA_EMAIL);

        // 1. Cliente cria o pedido.
        var createRes = mockMvc.perform(post("/api/support/tickets")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subject", "Dúvida sobre faturação", "category", "Faturação", "message", "Como funciona a faturação garantida?"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ABERTO"))
                .andExpect(jsonPath("$.reference").value(org.hamcrest.Matchers.matchesPattern("SUP-\\d{4}-\\d{5}")))
                .andReturn();
        String ticketId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText();

        // 2. Aparece na listagem própria do cliente.
        mockMvc.perform(get("/api/support/tickets").header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + ticketId + "')]").exists());

        // 3. Listagem admin, enriquecida com autor/empresa.
        var adminListRes = mockMvc.perform(get("/api/support/admin/tickets").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode adminList = objectMapper.readTree(adminListRes.getResponse().getContentAsString());
        JsonNode noAdminList = null;
        for (JsonNode t : adminList) if (ticketId.equals(t.get("id").asText())) noAdminList = t;
        assertTrue(noAdminList != null, "O ticket devia aparecer na listagem admin.");
        assertEquals("Ana Comprador", noAdminList.get("user").get("name").asText());
        assertEquals("Petro Angola Operações, Lda", noAdminList.get("company").asText());

        // Um utilizador que não é dono nem gere Suporte não vê o detalhe (404, não 403).
        mockMvc.perform(get("/api/support/tickets/" + ticketId).header("Authorization", "Bearer " + financeiroToken))
                .andExpect(status().isNotFound());

        // 4. Admin assume o ticket.
        mockMvc.perform(post("/api/support/admin/tickets/" + ticketId + "/assume").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EM_ANDAMENTO"));

        // Acima de 10MB o multer aborta com LIMIT_FILE_SIZE, que o errorHandler.js
        // traduz em 413 com esta frase. O fileFilter (tipo) corre ANTES do limite,
        // por isso um ficheiro grande do tipo errado continua a ser 422.
        byte[] enorme = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy("%PDF-1.4\n".getBytes(StandardCharsets.UTF_8), 0, enorme, 0, 9);
        mockMvc.perform(multipart("/api/support/tickets/" + ticketId + "/messages")
                        .file(new MockMultipartFile("attachment", "enorme.pdf", "application/pdf", enorme))
                        .param("body", "Segue o anexo.")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.error.code").value("LIMIT_FILE_SIZE"))
                .andExpect(jsonPath("$.error.message").value("O ficheiro é demasiado grande. Reduza o tamanho da imagem e tente novamente."));
        mockMvc.perform(multipart("/api/support/tickets/" + ticketId + "/messages")
                        .file(new MockMultipartFile("attachment", "enorme.txt", "text/plain", enorme))
                        .param("body", "Segue o anexo.")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value("Documento inválido — use PDF ou imagem (PNG/JPG)."));

        // 5. Admin responde com um anexo — o pedido passa a aguardar o cliente.
        var msgRes = mockMvc.perform(multipart("/api/support/tickets/" + ticketId + "/messages")
                        .file(new MockMultipartFile("attachment", "captura.png", "image/png", PNG_BYTES))
                        .param("body", "Pode explicar melhor o que não ficou claro?")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachmentUrl").isString())
                .andReturn();
        String attachmentUrl = objectMapper.readTree(msgRes.getResponse().getContentAsString()).get("attachmentUrl").asText();
        String attachmentFilename = attachmentUrl.substring(attachmentUrl.lastIndexOf('/') + 1);

        mockMvc.perform(get("/api/support/tickets/" + ticketId).header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AGUARDANDO_RESPOSTA"))
                .andExpect(jsonPath("$.statusLabel").value("Aguardando Cliente"));

        // 6. O DONO do ticket consegue ver o anexo. (charset=UTF-8 vem do
        // server.servlet.encoding.force=true, global à aplicação — ver M1;
        // inofensivo para uma imagem, os browsers ignoram-no aqui.)
        mockMvc.perform(get("/api/uploads/" + attachmentFilename).header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().stringValues("Content-Type", "image/png;charset=UTF-8"));

        // 7. Um estranho (não dono, não admin) NÃO consegue — 404, não 403 (não confirma a existência).
        mockMvc.perform(get("/api/uploads/" + attachmentFilename).header("Authorization", "Bearer " + financeiroToken))
                .andExpect(status().isNotFound());

        // 8. Sem sessão nenhuma, o anexo privado pede 401.
        mockMvc.perform(get("/api/uploads/" + attachmentFilename)).andExpect(status().isUnauthorized());

        // 9. Cliente responde — volta para EM_ANDAMENTO.
        mockMvc.perform(multipart("/api/support/tickets/" + ticketId + "/messages")
                        .param("body", "Sim, a dúvida é sobre o prazo de pagamento.")
                        .header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/support/tickets/" + ticketId).header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EM_ANDAMENTO"));

        // 10. Marcar como lidas + contador.
        mockMvc.perform(post("/api/support/tickets/" + ticketId + "/read").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        // 11. Resolver e reabrir, com auditoria.
        mockMvc.perform(post("/api/support/admin/tickets/" + ticketId + "/resolve").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVIDO"));
        mockMvc.perform(post("/api/support/admin/tickets/" + ticketId + "/reopen").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EM_ANDAMENTO"));

        var auditRes = mockMvc.perform(get("/api/admin/audit-logs").header("Authorization", "Bearer " + adminToken)
                        .param("action", "SUPORTE_TICKET_RESOLVIDO"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode auditBody = objectMapper.readTree(auditRes.getResponse().getContentAsString());
        boolean auditoriaEncontrada = false;
        for (JsonNode item : auditBody.get("items")) {
            if (ticketId.equals(item.get("entityId").asText(null))) auditoriaEncontrada = true;
        }
        assertTrue(auditoriaEncontrada, "SUPORTE_TICKET_RESOLVIDO devia ter ficado no trilho de auditoria.");
    }

    /** tests/support.test.js "rejeita ticket sem assunto/mensagem (400)" — 400 INVALID escrito à mão, não 422 do zod. */
    @Test
    void rejeitaTicketSemAssuntoOuMensagem() throws Exception {
        String token = login(COMPRADOR_EMAIL);
        mockMvc.perform(post("/api/support/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("subject", "", "message", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID"))
                .andExpect(jsonPath("$.error.message").value("Assunto e mensagem são obrigatórios."))
                .andExpect(jsonPath("$.error.details").doesNotExist());
        // Sem corpo nenhum (`req.body?.subject`) é o mesmo 400.
        mockMvc.perform(post("/api/support/tickets").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID"));
    }

    /** Os outros `res.status(400).json({ error: { code: 'INVALID' } })` de supportRoutes.js. */
    @Test
    void estadoInvalidoETransferenciaSemDestinoSao400Invalid() throws Exception {
        String compradorToken = login(COMPRADOR_EMAIL);
        String adminToken = login(ADMIN_SISTEMA_EMAIL);
        var createRes = mockMvc.perform(post("/api/support/tickets")
                        .header("Authorization", "Bearer " + compradorToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("subject", "Estado", "message", "Teste de estado"))))
                .andExpect(status().isCreated())
                .andReturn();
        String ticketId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(patch("/api/support/tickets/" + ticketId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("status", "INEXISTENTE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID"))
                .andExpect(jsonPath("$.error.message").value("Estado inválido."));

        mockMvc.perform(post("/api/support/admin/tickets/" + ticketId + "/transfer")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID"))
                .andExpect(jsonPath("$.error.message").value("Indique o assessor de destino."));
    }

    /**
     * `res.status(201).json(ticket)` devolve a linha crua do Prisma: as colunas
     * a null ({@code companyId} de quem não tem empresa, {@code assignedToId}
     * enquanto ninguém assumiu) saem como {@code null}, não desaparecem. Na
     * listagem admin, {@code company} sai a {@code null} pela mesma razão.
     */
    @Test
    void criacaoDevolveALinhaCruaComAsChavesNulas() throws Exception {
        String adminToken = login(ADMIN_SISTEMA_EMAIL); // Admin do Sistema: sem empresa → companyId null.
        var createRes = mockMvc.perform(post("/api/support/tickets")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("subject", "Forma da resposta", "message", "Chaves nulas presentes"))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode ticket = objectMapper.readTree(createRes.getResponse().getContentAsString());
        for (String chave : List.of("id", "reference", "userId", "companyId", "subject", "category", "message", "status",
                "assignedToId", "createdAt", "updatedAt")) {
            assertTrue(ticket.has(chave), "A chave '" + chave + "' devia estar presente, como na linha do Prisma.");
        }
        assertTrue(ticket.get("companyId").isNull(), "companyId devia sair como null explícito.");
        assertTrue(ticket.get("assignedToId").isNull(), "assignedToId devia sair como null explícito.");
        assertEquals("Geral", ticket.get("category").asText());
        // Só GET /tickets/:id acrescenta statusLabel; só /admin/tickets acrescenta user/company.
        assertFalse(ticket.has("statusLabel"));
        assertFalse(ticket.has("user"));
        assertFalse(ticket.has("company"));
        String ticketId = ticket.get("id").asText();

        var listRes = mockMvc.perform(get("/api/support/admin/tickets").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode naLista = null;
        for (JsonNode t : objectMapper.readTree(listRes.getResponse().getContentAsString())) {
            if (ticketId.equals(t.get("id").asText())) naLista = t;
        }
        assertTrue(naLista != null, "O ticket devia aparecer na listagem admin.");
        assertEquals(ADMIN_SISTEMA_EMAIL, naLista.get("user").get("email").asText());
        assertTrue(naLista.has("company") && naLista.get("company").isNull(), "company devia sair como null explícito (`|| null`).");
        assertFalse(naLista.has("statusLabel"));

        mockMvc.perform(get("/api/support/tickets/" + ticketId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusLabel").isString())
                .andExpect(jsonPath("$.user").doesNotExist());
    }

    @Test
    void agenteNaoAdminNaoAcedeAoPainel() throws Exception {
        String token = login(COMPRADOR_EMAIL);
        mockMvc.perform(get("/api/support/admin/queue").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}
