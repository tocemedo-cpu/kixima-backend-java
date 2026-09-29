package ao.kixima.cobranca;

import ao.kixima.company.CompanyPlan;
import ao.kixima.plan.PlanService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Espelha tests/assinatura.test.js e tests/assinatura-gateway.test.js: o
 * plano NÃO muda antes de a KIXIMA confirmar o dinheiro; o preço congela; os
 * canais automáticos recusam-se a fingir; o webhook confirma sem sessão.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AssinaturaControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPANY_ADMIN_EMAIL = "admin@petroangola.co.ao";
    private static final String FINANCEIRO_EMAIL = "financeiro@petroangola.co.ao";
    private static final String ADMIN_SISTEMA_EMAIL = "admin@kixima.co.ao";
    private static final byte[] COMPROVATIVO = "%PDF-1.4 transferencia BAI subscricao".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlanService planService;

    @Autowired
    private AssinaturaService assinaturaService;

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

    private String companyId() {
        return jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = 'AO-CLI-0001'", String.class);
    }

    private void porNoPlano(String companyId, CompanyPlan plano) {
        jdbcTemplate.update("UPDATE companies SET plan = ?::\"CompanyPlan\", search_rank = ?, plano_valido_ate = NULL WHERE id = ?",
                plano.name(), planService.rankDoPlano(plano), companyId);
        entityManager.clear();
    }

    private JsonNode pedir(String token, String plano, int esperado) throws Exception {
        var res = mockMvc.perform(post("/api/assinatura/pedir").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(objectMapper.writeValueAsString(Map.of("plano", plano))))
                .andExpect(status().is(esperado)).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private void comprovativo(String token, String cobrancaId) throws Exception {
        mockMvc.perform(multipart("/api/assinatura/" + cobrancaId + "/comprovativo")
                        .file(new MockMultipartFile("comprovativo", "transferencia.pdf", "application/pdf", COMPROVATIVO))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPROVATIVO_ENVIADO"))
                .andExpect(jsonPath("$.comprovativoUrl").isString())
                .andExpect(jsonPath("$.submetidoEm").isString());
    }

    private String planoAtual(String companyId) {
        entityManager.flush();
        return jdbcTemplate.queryForObject("SELECT plan::text FROM companies WHERE id = ?", String.class, companyId);
    }

    @Test
    void pedirEmiteACobrancaENaoMudaOPlano() throws Exception {
        String companyId = companyId();
        porNoPlano(companyId, CompanyPlan.CORE);
        String adminEmpresa = login(COMPANY_ADMIN_EMAIL);

        JsonNode cobranca = pedir(adminEmpresa, "PRO", 201);
        assertThat(cobranca.get("status").asText()).isEqualTo("PENDENTE");
        assertThat(cobranca.get("planoNovo").asText()).isEqualTo("PRO");
        assertThat(cobranca.get("referencia").asText()).matches("^SUB-\\d{4}-\\d{6}$");
        assertThat(planoAtual(companyId)).isEqualTo("CORE");
        // O preço fica congelado na cobrança.
        PlanService.Preco tabela = planService.preco(CompanyPlan.PRO);
        assertThat(new java.math.BigDecimal(cobranca.get("valorUsd").asText())).isEqualByComparingTo(tabela.valorUsd());
        assertThat(cobranca.get("periodo").asText()).isEqualTo(tabela.periodo());
        assertThat(cobranca.get("meses").asInt()).isEqualTo(tabela.meses());

        // Não deixa abrir uma segunda com uma por liquidar — e a mensagem diz qual.
        JsonNode segunda = pedir(adminEmpresa, "CORE", 409);
        assertThat(segunda.get("error").get("message").asText()).contains(cobranca.get("referencia").asText());
        // Plano inexistente (422); o Financeiro não escolhe o plano (403); sem sessão (401).
        mockMvc.perform(post("/api/assinatura/cancelar-nada")).andExpect(status().isUnauthorized());
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'SUBSCRICAO_PEDIDA' AND entity_id = ?", Integer.class,
                cobranca.get("id").asText())).isEqualTo(1);
        mockMvc.perform(post("/api/assinatura/pedir").header("Authorization", "Bearer " + login(FINANCEIRO_EMAIL))
                        .contentType("application/json").content("{\"plano\":\"PRO\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/assinatura")).andExpect(status().isUnauthorized());

        // Cancelar exige motivo; cancelada com motivo liberta o caminho para um novo pedido.
        mockMvc.perform(post("/api/assinatura/" + cobranca.get("id").asText() + "/cancelar").header("Authorization", "Bearer " + adminEmpresa)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/assinatura/" + cobranca.get("id").asText() + "/cancelar").header("Authorization", "Bearer " + adminEmpresa)
                        .contentType("application/json").content("{\"motivo\":\"Pedido por engano\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELADA"))
                .andExpect(jsonPath("$.notas").value("Pedido por engano"));
        pedir(adminEmpresa, "PLATINUM", 422);
        pedir(adminEmpresa, "CORE", 201);
    }

    /**
     * A rota usa `uploadDocuments` (10MB): acima disso o multer aborta com
     * LIMIT_FILE_SIZE e o errorHandler.js responde 413 com esta frase — o
     * `code` do multer viaja tal e qual. O formato é verificado antes do
     * tamanho (fileFilter primeiro), por isso um ficheiro grande de tipo
     * errado continua a ser 422.
     */
    @Test
    void comprovativoAcimaDe10MbDa413ComoOMulter() throws Exception {
        porNoPlano(companyId(), CompanyPlan.CORE);
        String adminEmpresa = login(COMPANY_ADMIN_EMAIL);
        String id = pedir(adminEmpresa, "PRO", 201).get("id").asText();

        byte[] grande = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy(COMPROVATIVO, 0, grande, 0, COMPROVATIVO.length);
        mockMvc.perform(multipart("/api/assinatura/" + id + "/comprovativo")
                        .file(new MockMultipartFile("comprovativo", "enorme.pdf", "application/pdf", grande))
                        .header("Authorization", "Bearer " + adminEmpresa))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.error.code").value("LIMIT_FILE_SIZE"))
                .andExpect(jsonPath("$.error.message").value("O ficheiro é demasiado grande. Reduza o tamanho da imagem e tente novamente."));
        mockMvc.perform(multipart("/api/assinatura/" + id + "/comprovativo")
                        .file(new MockMultipartFile("comprovativo", "enorme.txt", "text/plain", grande))
                        .header("Authorization", "Bearer " + adminEmpresa))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value("Documento inválido — use PDF ou imagem (PNG/JPG)."));

        // A cobrança continua sem comprovativo; exatamente 10MB ainda passa (o limite do multer é "maior do que").
        mockMvc.perform(get("/api/assinatura").header("Authorization", "Bearer " + adminEmpresa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emAberto.status").value("PENDENTE"));
        byte[] noLimite = new byte[10 * 1024 * 1024];
        System.arraycopy(COMPROVATIVO, 0, noLimite, 0, COMPROVATIVO.length);
        mockMvc.perform(multipart("/api/assinatura/" + id + "/comprovativo")
                        .file(new MockMultipartFile("comprovativo", "limite.pdf", "application/pdf", noLimite))
                        .header("Authorization", "Bearer " + adminEmpresa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPROVATIVO_ENVIADO"));
    }

    @Test
    void aConfirmacaoEOUnicoSitioOndeOPlanoMuda() throws Exception {
        String companyId = companyId();
        porNoPlano(companyId, CompanyPlan.CORE);
        String adminEmpresa = login(COMPANY_ADMIN_EMAIL);
        String financeiro = login(FINANCEIRO_EMAIL);
        String adminSistema = login(ADMIN_SISTEMA_EMAIL);

        String id = pedir(adminEmpresa, "PRO", 201).get("id").asText();
        // Sem comprovativo não passa a COMPROVATIVO_ENVIADO; confirmar sem comprovativo é recusado.
        mockMvc.perform(multipart("/api/assinatura/" + id + "/comprovativo").header("Authorization", "Bearer " + adminEmpresa))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/assinatura/" + id + "/confirmar").header("Authorization", "Bearer " + adminSistema)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(planoAtual(companyId)).isEqualTo("CORE");

        // A Subscrição é só do Company Admin — o Financeiro não tem acesso nenhum aqui.
        mockMvc.perform(multipart("/api/assinatura/" + id + "/comprovativo")
                        .file(new MockMultipartFile("comprovativo", "transferencia.pdf", "application/pdf", COMPROVATIVO))
                        .header("Authorization", "Bearer " + financeiro))
                .andExpect(status().isForbidden());

        // O Company Admin carrega o comprovativo — e o plano ainda não mudou.
        comprovativo(adminEmpresa, id);
        assertThat(planoAtual(companyId)).isEqualTo("CORE");
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE type = 'SUBSCRICAO_COMPROVATIVO' AND related_entity_id = ?", Integer.class, id))
                .isGreaterThan(0);

        // A empresa não confirma a sua própria cobrança; a fila é só da KIXIMA.
        mockMvc.perform(post("/api/assinatura/" + id + "/confirmar").header("Authorization", "Bearer " + adminEmpresa)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/assinatura/fila").header("Authorization", "Bearer " + adminEmpresa)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/assinatura/fila").header("Authorization", "Bearer " + adminSistema))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.porConfirmar").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.emAberto[?(@.id == '" + id + "')].company.name").value("Petro Angola Operações, Lda"));

        mockMvc.perform(post("/api/assinatura/" + id + "/confirmar").header("Authorization", "Bearer " + adminSistema)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMADA"))
                .andExpect(jsonPath("$.validoAte").isString());
        assertThat(planoAtual(companyId)).isEqualTo("PRO");
        Map<String, Object> empresa = jdbcTemplate.queryForMap("SELECT search_rank, plano_valido_ate FROM companies WHERE id = ?", companyId);
        assertThat(empresa.get("search_rank")).isEqualTo(planService.rankDoPlano(CompanyPlan.PRO));
        assertThat(empresa.get("plano_valido_ate")).isNotNull();
        String detail = jdbcTemplate.queryForObject("SELECT detail::text FROM audit_logs WHERE action = 'SUBSCRICAO_CONFIRMADA' AND entity_id = ?", String.class, id);
        assertThat(detail.replace(" ", "")).contains("\"para\":\"PRO\"");

        // Confirmar duas vezes não estende o prazo outra vez.
        mockMvc.perform(post("/api/assinatura/" + id + "/confirmar").header("Authorization", "Bearer " + adminSistema)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isConflict());
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT plano_valido_ate FROM companies WHERE id = ?", java.sql.Timestamp.class, companyId))
                .isEqualTo(empresa.get("plano_valido_ate"));
    }

    @Test
    void validadeDescidasDePlanoEEstado() throws Exception {
        // A conta que decide quantos dias de acesso alguém comprou.
        Instant agora = Instant.parse("2026-03-01T00:00:00Z");
        assertThat(AssinaturaService.novoValidoAte(Instant.parse("2026-06-01T00:00:00Z"), 3, agora).toString()).startsWith("2026-09-01");
        assertThat(AssinaturaService.novoValidoAte(Instant.parse("2026-06-01T00:00:00Z"), 3, Instant.parse("2026-08-01T00:00:00Z")).toString()).startsWith("2026-11-01");
        assertThat(AssinaturaService.novoValidoAte(null, 12, Instant.parse("2026-08-01T00:00:00Z")).toString()).startsWith("2027-08-01");
        // Dados bancários da KIXIMA: o IBAN é o único indispensável; a moeda tem omissão.
        assertThat(AssinaturaService.dadosBancarios(null, null, "   ", null, null).configurado()).isFalse();
        AssinaturaService.DadosBancarios b = AssinaturaService.dadosBancarios("KIXIMA", "BAI", "AO06 0000", null, null);
        assertThat(b.configurado()).isTrue();
        assertThat(b.moeda()).isEqualTo("USD");

        String companyId = companyId();
        String adminEmpresa = login(COMPANY_ADMIN_EMAIL);

        // Não desce para um plano com menos lugares do que os ocupados — o impedimento vem em código e números.
        porNoPlano(companyId, CompanyPlan.PRO);
        int ocupados = assinaturaService.lugaresOcupados(companyId);
        int lugaresBase = planService.limite(CompanyPlan.BASE, ao.kixima.plan.PlanLimit.LUGARES_INCLUIDOS);
        assertThat(ocupados).isGreaterThan(lugaresBase);
        JsonNode recusa = pedir(adminEmpresa, "BASE", 400);
        assertThat(recusa.get("error").get("message").asText()).contains(String.valueOf(ocupados));

        JsonNode estado = objectMapper.readTree(mockMvc.perform(get("/api/assinatura").header("Authorization", "Bearer " + adminEmpresa))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(estado.get("banco").has("configurado")).isTrue();
        assertThat(estado.get("opcoes")).hasSize(planService.escada().size());
        assertThat(estado.get("estadoSubscricao").asText()).isEqualTo("ATIVA");
        int atuais = 0;
        for (JsonNode o : estado.get("opcoes")) {
            CompanyPlan plano = CompanyPlan.valueOf(o.get("plano").asText());
            assertThat(new java.math.BigDecimal(o.get("preco").get("valorUsd").asText())).isEqualByComparingTo(planService.preco(plano).valorUsd());
            assertThat(o.get("direcao").asText()).isIn("SUBIR", "DESCER", "RENOVAR");
            if (o.get("atual").asBoolean()) atuais++;
            if (plano == CompanyPlan.BASE) {
                assertThat(o.get("impedimento").get("codigo").asText()).isEqualTo("LUGARES_INSUFICIENTES");
                assertThat(o.get("impedimento").get("lugares").asInt()).isEqualTo(lugaresBase);
                assertThat(o.get("impedimento").get("ocupados").asInt()).isEqualTo(ocupados);
                assertThat(AssinaturaService.impedimentoEmTexto(new AssinaturaService.Impedimento("LUGARES_INSUFICIENTES", null, null, "BASE", lugaresBase, ocupados)))
                        .contains(String.valueOf(ocupados));
            }
        }
        assertThat(atuais).isEqualTo(1);

        // Renovar o plano atual não é travado pelos lugares ocupados; descer continua travado.
        porNoPlano(companyId, CompanyPlan.BASE);
        JsonNode estadoBase = objectMapper.readTree(mockMvc.perform(get("/api/assinatura").header("Authorization", "Bearer " + adminEmpresa))
                .andReturn().getResponse().getContentAsString());
        for (JsonNode o : estadoBase.get("opcoes")) {
            if ("BASE".equals(o.get("plano").asText())) {
                assertThat(o.get("direcao").asText()).isEqualTo("RENOVAR");
                assertThat(o.get("impedimento").isNull()).isTrue();
            }
        }
        pedir(adminEmpresa, "BASE", 201);

        // Uma subscrição vencida aparece na fila em vez de descer o plano sozinha (GRACE vs RESTRITA separadas).
        jdbcTemplate.update("UPDATE companies SET plan = 'PRO', plano_valido_ate = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(3, ChronoUnit.DAYS)), companyId);
        entityManager.clear();
        JsonNode fila = objectMapper.readTree(mockMvc.perform(get("/api/assinatura/fila").header("Authorization", "Bearer " + login(ADMIN_SISTEMA_EMAIL)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode vencida = null;
        for (JsonNode v : fila.get("vencidas")) if (companyId.equals(v.get("id").asText())) vencida = v;
        assertThat(vencida).isNotNull();
        assertThat(vencida.get("diasVencida").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(fila.get("emGrace").toString()).contains(companyId);
        assertThat(fila.get("restritas").toString()).doesNotContain(companyId);
        assertThat(planoAtual(companyId)).isEqualTo("PRO");
    }

    @Test
    void canaisAutomaticosRecusamSeAFingirEOWebhookConfirmaSemSessao() throws Exception {
        String companyId = companyId();
        porNoPlano(companyId, CompanyPlan.BASE);
        String adminEmpresa = login(COMPANY_ADMIN_EMAIL);
        String financeiro = login(FINANCEIRO_EMAIL);

        mockMvc.perform(get("/api/assinatura/canais")).andExpect(status().isUnauthorized());
        // A Subscrição é só do Company Admin — o Financeiro não vê sequer os canais.
        mockMvc.perform(get("/api/assinatura/canais").header("Authorization", "Bearer " + financeiro))
                .andExpect(status().isForbidden());
        JsonNode canais = objectMapper.readTree(mockMvc.perform(get("/api/assinatura/canais").header("Authorization", "Bearer " + adminEmpresa))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(canais.fieldNames()).toIterable().containsExactlyInAnyOrder("BAI", "BFA", "EMIS_MULTICAIXA", "PAYPAY", "STANDARD_BANK_ANGOLA");
        canais.forEach(c -> assertThat(c.get("disponivel").asBoolean()).isFalse());

        // Sem credenciais reais, recusa-se em vez de fingir sucesso — e não fica meio-iniciado.
        String id = pedir(adminEmpresa, "CORE", 201).get("id").asText();
        mockMvc.perform(post("/api/assinatura/" + id + "/pagar-com").header("Authorization", "Bearer " + adminEmpresa)
                        .contentType("application/json").content("{\"canal\":\"EMIS_MULTICAIXA\",\"telemovel\":\"900000000\"}"))
                .andExpect(status().isInternalServerError());
        Map<String, Object> depois = jdbcTemplate.queryForMap("SELECT canal::text, referencia_externa FROM plano_cobrancas WHERE id = ?", id);
        assertThat(depois.get("canal")).isEqualTo("TRANSFERENCIA_MANUAL");
        assertThat(depois.get("referencia_externa")).isNull();
        assertThatThrownBy(() -> assinaturaService.iniciarPagamentoGateway(companyId, id, "EMIS_MULTICAIXA", "900000000", null))
                .hasMessageContaining("não está configurado");
        mockMvc.perform(post("/api/assinatura/" + id + "/pagar-com").header("Authorization", "Bearer " + adminEmpresa)
                        .contentType("application/json").content("{\"canal\":\"CARTAO_MAGICO\"}"))
                .andExpect(status().isUnprocessableEntity());

        // confirmarViaGateway: ativa o plano sem comprovativo, regista o canal na auditoria, é idempotente e recusa referências erradas.
        jdbcTemplate.update("UPDATE plano_cobrancas SET canal = 'EMIS_MULTICAIXA', referencia_externa = ? WHERE id = ?", "TXN-" + id, id);
        entityManager.clear();
        CobrancaDtos.PlanoCobrancaDto confirmada = assinaturaService.confirmarViaGateway(id, CanalCobranca.EMIS_MULTICAIXA, "TXN-" + id);
        assertThat(confirmada.status()).isEqualTo("CONFIRMADA");
        assertThat(confirmada.confirmadaPor()).isNull();
        assertThat(planoAtual(companyId)).isEqualTo("CORE");
        Map<String, Object> auditoria = jdbcTemplate.queryForMap("SELECT actor_id, actor_name FROM audit_logs WHERE action = 'SUBSCRICAO_CONFIRMADA' AND entity_id = ?", id);
        assertThat(auditoria.get("actor_id")).isNull();
        assertThat(auditoria.get("actor_name").toString()).containsIgnoringCase("automático");
        CobrancaDtos.PlanoCobrancaDto segunda = assinaturaService.confirmarViaGateway(id, CanalCobranca.EMIS_MULTICAIXA, "TXN-" + id);
        assertThat(segunda.confirmadaEm()).isEqualTo(confirmada.confirmadaEm());

        // O PRO nunca aceita um canal automático.
        porNoPlano(companyId, CompanyPlan.CORE);
        String pro = pedir(adminEmpresa, "PRO", 201).get("id").asText();
        mockMvc.perform(post("/api/assinatura/" + pro + "/pagar-com").header("Authorization", "Bearer " + adminEmpresa)
                        .contentType("application/json").content("{\"canal\":\"EMIS_MULTICAIXA\",\"telemovel\":\"900000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsStringIgnoringCase("transferência bancária")));
        assertThatThrownBy(() -> assinaturaService.confirmarViaGateway(pro, CanalCobranca.EMIS_MULTICAIXA, "TXN-DE-OUTRO"))
                .hasMessageContaining("não corresponde");

        // Webhook: sem sessão (nunca 401); canal desconhecido devolve 404 explícito.
        mockMvc.perform(post("/api/webhooks/pagamento/emis_multicaixa").contentType("application/json").content("{\"id\":\"TXN-1\"}"))
                .andExpect(status().is(org.hamcrest.Matchers.not(401)));
        mockMvc.perform(post("/api/webhooks/pagamento/banco-inventado").contentType("application/json").content("{\"id\":\"TXN-1\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CANAL_DESCONHECIDO"));

        // Tabela de planos — pública.
        mockMvc.perform(get("/api/planos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planos.length()").value(3))
                .andExpect(jsonPath("$.planos[2].plano").value("PRO"))
                .andExpect(jsonPath("$.planos[2].preco.porMesUsd").value(416.67))
                .andExpect(jsonPath("$.taxaPorTransacao.porOrdemUsd").value(8));
    }
}
