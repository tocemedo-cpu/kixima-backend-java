package ao.kixima.porobo;

import ao.kixima.testsupport.Contas;
import ao.kixima.testsupport.Contas.Persona;
import ao.kixima.testsupport.Dados;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP do PO Robot para o frontend Vue (docs/api-po-robot.md): COMPRADOR e Company Admin têm acesso,
 * Fornecedor e Financeiro não; previsões, propostas, execuções e actividades; ajustar, submeter e rejeitar com os
 * códigos de erro do contrato; e nada de aprovação nem pagamento.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PoRoboPropostasControllerTest {

    private static final String PASSWORD = Contas.senha();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PoRoboService poRoboService;

    @PersistenceContext
    private EntityManager entityManager;

    private String petroangolaId;
    private String valvulaId;
    private String mangueiraId;
    private String compradorToken;
    private String adminToken;

    @BeforeEach
    void fixtures() throws Exception {
        petroangolaId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, Dados.nifCliente());
        valvulaId = jdbcTemplate.queryForObject("SELECT id FROM products WHERE name LIKE 'Válvula de esfera%' AND active AND supplier_id = ?", String.class, Dados.idFornecedora());
        mangueiraId = jdbcTemplate.queryForObject("SELECT id FROM products WHERE name LIKE 'Mangueira hidráulica%' AND active AND supplier_id = ?", String.class, Dados.idFornecedora());
        compradorToken = login(Persona.COMPRADOR);
        adminToken = login(Persona.COMPANY_ADMIN);
    }

    private String login(Persona persona) throws Exception {
        var res = mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("email", Contas.email(persona), "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    private void ativarAddon() {
        jdbcTemplate.update("INSERT INTO company_addons (id, company_id, addon_key, status, activated_at, valido_ate, created_at, updated_at) "
                        + "VALUES (?, ?, 'PO_ROBOT', 'ATIVO', now(), ?, now(), now())",
                UUID.randomUUID().toString(), petroangolaId, java.sql.Timestamp.from(Instant.now().plus(Duration.ofDays(30))));
    }

    private MockHttpServletRequestBuilder com(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", "Bearer " + token);
    }

    private JsonNode json(MockHttpServletRequestBuilder b, int esperado) throws Exception {
        var r = mockMvc.perform(b).andExpect(status().is(esperado)).andReturn();
        String corpo = r.getResponse().getContentAsString();
        return corpo.isBlank() ? null : objectMapper.readTree(corpo);
    }

    private String criarRegra(String token, String productId, int quantidade, Object limite) throws Exception {
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("productId", productId);
        corpo.put("mediaOrigem", "MANUAL");
        corpo.put("mediaMensal", 30);
        corpo.put("periodicidade", "MENSAL");
        corpo.put("quantidade", quantidade);
        corpo.put("limiteMaximoUsd", limite);
        corpo.put("stockMinimo", 4);
        corpo.put("prazoEntregaDias", 7);
        return json(com(post("/api/po-robot/regras"), token).contentType("application/json").content(objectMapper.writeValueAsString(corpo)), 201)
                .get("id").asText();
    }

    private void correrORobot() {
        entityManager.flush();
        entityManager.clear();
        poRoboService.executarCiclo();
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void soCompradorECompanyAdminTemAcessoEOsOutrosPerfisRecebem403() throws Exception {
        String fornecedor = login(Persona.FORNECEDOR);
        String financeiro = login(Persona.FINANCEIRO);
        String[] leituras = {"/regras", "/previsoes", "/propostas", "/execucoes", "/actividades", "/media-sugerida/" + valvulaId};
        for (String token : new String[]{fornecedor, financeiro}) {
            for (String rota : leituras) {
                mockMvc.perform(com(get("/api/po-robot" + rota), token)).andExpect(status().isForbidden());
            }
            mockMvc.perform(com(post("/api/po-robot/regras"), token).contentType("application/json").content("{}")).andExpect(status().isForbidden());
            mockMvc.perform(com(put("/api/po-robot/regras/x"), token).contentType("application/json").content("{}")).andExpect(status().isForbidden());
            mockMvc.perform(com(delete("/api/po-robot/regras/x"), token)).andExpect(status().isForbidden());
            mockMvc.perform(com(put("/api/po-robot/propostas/x"), token).contentType("application/json").content("{\"quantidade\":1}")).andExpect(status().isForbidden());
            mockMvc.perform(com(post("/api/po-robot/propostas/x/submeter"), token)).andExpect(status().isForbidden());
            mockMvc.perform(com(post("/api/po-robot/propostas/x/rejeitar"), token).contentType("application/json").content("{\"motivo\":\"x\"}")).andExpect(status().isForbidden());
        }
        for (String token : new String[]{compradorToken, adminToken}) {
            for (String rota : leituras) {
                if (rota.startsWith("/media")) continue; // pede o add-on activo
                mockMvc.perform(com(get("/api/po-robot" + rota), token)).andExpect(status().isOk());
            }
        }
        for (String rota : leituras) mockMvc.perform(get("/api/po-robot" + rota)).andExpect(status().isUnauthorized());
    }

    @Test
    void naoExisteNenhumEndpointDeAprovacaoNemDePagamento() throws Exception {
        ativarAddon();
        String regra = criarRegra(compradorToken, valvulaId, 2, null);
        correrORobot();
        String proposta = json(com(get("/api/po-robot/propostas"), compradorToken), 200).get(0).get("id").asText();
        for (String rota : new String[]{"/aprovar", "/pagar", "/approve", "/pay"}) {
            int estado = mockMvc.perform(com(post("/api/po-robot/propostas/" + proposta + rota), compradorToken)).andReturn().getResponse().getStatus();
            assertThat(estado).as("POST %s", rota).isIn(404, 405);
        }
        assertThat(regra).isNotBlank();
    }

    @Test
    void cicloDoRobotEOContratoDeCadaListagem() throws Exception {
        ativarAddon();
        criarRegra(compradorToken, valvulaId, 5, 2000); // reduzida pelo limite: 5 → 2
        criarRegra(adminToken, mangueiraId, 3, null);
        correrORobot();

        JsonNode regras = json(com(get("/api/po-robot/regras"), compradorToken), 200);
        assertThat(regras).hasSize(2);
        for (JsonNode r : regras) {
            assertThat(r.get("ultimaExecucaoEm").isNull()).isFalse();
            assertThat(r.get("stockMinimo").asInt()).isEqualTo(4);
            assertThat(r.get("prazoEntregaDias").asInt()).isEqualTo(7);
            assertThat(r.get("product").get("supplier").get("name").asText()).isNotBlank();
            assertThat(r.has("demo")).as("em produção não há dados de demonstração").isFalse();
        }

        JsonNode previsoes = json(com(get("/api/po-robot/previsoes"), compradorToken), 200);
        assertThat(previsoes).hasSize(2);
        for (JsonNode p : previsoes) {
            assertThat(p.get("stockActual").isNull()).as("sem fonte fiável do inventário do comprador").isTrue();
            assertThat(p.get("origemDados").asText()).isEqualTo("MANUAL");
            assertThat(p.get("justificacao")).isNotEmpty();
            assertThat(p.get("justificacao").get(0).has("rotulo")).isTrue();
            assertThat(p.get("product").get("name").asText()).isNotBlank();
            assertThat(p.get("execucaoId").asText()).isNotBlank();
        }

        JsonNode propostas = json(com(get("/api/po-robot/propostas"), adminToken), 200);
        assertThat(propostas).hasSize(2);
        for (JsonNode p : propostas) {
            assertThat(p.get("estado").asText()).isEqualTo("PENDENTE_REVISAO");
            assertThat(p.get("quantidade").asInt()).isPositive();
            assertThat(p.get("purchaseOrderId").isNull()).as("o robot não cria POs").isTrue();
            assertThat(p.get("supplier").get("name").asText()).isNotBlank();
            assertThat(p.get("moeda").asText()).isEqualTo("AOA");
        }
        JsonNode reduzida = propostas.findParents("limiteAplicado").stream().filter(p -> p.get("limiteAplicado").asBoolean()).findFirst().orElseThrow();
        assertThat(reduzida.get("quantidade").asInt()).isEqualTo(2);
        assertThat(reduzida.get("quantidadeOriginal").asInt()).isEqualTo(5);

        JsonNode execucoes = json(com(get("/api/po-robot/execucoes"), compradorToken), 200);
        assertThat(execucoes).hasSize(1);
        assertThat(execucoes.get(0).get("estado").asText()).isEqualTo("CONCLUIDA");
        assertThat(execucoes.get(0).get("origem").asText()).isEqualTo("AGENDADA");
        assertThat(execucoes.get(0).get("regrasAvaliadas").asInt()).isEqualTo(2);
        assertThat(execucoes.get(0).get("propostasCriadas").asInt()).isEqualTo(2);
        assertThat(execucoes.get(0).get("erros")).isEmpty();
        assertThat(execucoes.get(0).get("terminadaEm").isNull()).isFalse();

        JsonNode actividades = json(com(get("/api/po-robot/actividades"), compradorToken), 200);
        assertThat(actividades.findValuesAsText("tipo")).contains("REGRA_CRIADA", "EXECUCAO", "PROPOSTA_CRIADA");
        for (JsonNode a : actividades) {
            if (a.get("tipo").asText().startsWith("PROPOSTA_CRIADA") || a.get("tipo").asText().equals("EXECUCAO")) {
                assertThat(a.get("autor").isNull()).as("autor null = o robot").isTrue();
            }
            assertThat(a.get("descricao").asText()).isNotBlank();
        }
    }

    @Test
    void ajustarSubmeterERejeitarComOsCodigosDoContrato() throws Exception {
        ativarAddon();
        criarRegra(compradorToken, valvulaId, 4, null);
        criarRegra(compradorToken, mangueiraId, 3, null);
        correrORobot();
        JsonNode propostas = json(com(get("/api/po-robot/propostas"), compradorToken), 200);
        String idValvula = null, idMangueira = null;
        for (JsonNode p : propostas) {
            if (p.get("productId").asText().equals(valvulaId)) idValvula = p.get("id").asText();
            else idMangueira = p.get("id").asText();
        }

        // Ajustar: inteiro > 0, senão 422; proposta inexistente, 404.
        for (String invalido : new String[]{"{\"quantidade\":0}", "{\"quantidade\":-1}", "{\"quantidade\":2.5}", "{\"quantidade\":\"3\"}", "{}"}) {
            mockMvc.perform(com(put("/api/po-robot/propostas/" + idValvula), compradorToken).contentType("application/json").content(invalido))
                    .andExpect(status().isUnprocessableEntity());
        }
        mockMvc.perform(com(put("/api/po-robot/propostas/nao-existe"), compradorToken).contentType("application/json").content("{\"quantidade\":1}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(com(put("/api/po-robot/propostas/" + idValvula), compradorToken).contentType("application/json").content("{\"quantidade\":6}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantidade").value(6))
                .andExpect(jsonPath("$.quantidadeOriginal").value(4))
                .andExpect(jsonPath("$.estado").value("PENDENTE_REVISAO"));

        // Submeter: cria a PO por aprovar e devolve a sua referência; uma segunda vez, 409.
        var submetida = mockMvc.perform(com(post("/api/po-robot/propostas/" + idValvula + "/submeter"), compradorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("SUBMETIDA"))
                .andExpect(jsonPath("$.purchaseOrderId").isNotEmpty())
                .andExpect(jsonPath("$.purchaseOrderReference").isNotEmpty())
                .andExpect(jsonPath("$.revistaPor.name").isNotEmpty())
                .andReturn();
        String poId = objectMapper.readTree(submetida.getResponse().getContentAsString()).get("purchaseOrderId").asText();
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM purchase_orders WHERE id = ?", String.class, poId)).isEqualTo("AGUARDANDO_APROVACAO");
        mockMvc.perform(com(post("/api/po-robot/propostas/" + idValvula + "/submeter"), adminToken)).andExpect(status().isConflict());
        mockMvc.perform(com(put("/api/po-robot/propostas/" + idValvula), compradorToken).contentType("application/json").content("{\"quantidade\":1}"))
                .andExpect(status().isConflict());

        // Rejeitar: motivo obrigatório (422); depois, 200; decidida, 409.
        mockMvc.perform(com(post("/api/po-robot/propostas/" + idMangueira + "/rejeitar"), compradorToken).contentType("application/json").content("{\"motivo\":\"  \"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(com(post("/api/po-robot/propostas/" + idMangueira + "/rejeitar"), compradorToken).contentType("application/json").content("{}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(com(post("/api/po-robot/propostas/" + idMangueira + "/rejeitar"), adminToken).contentType("application/json").content("{\"motivo\":\"Já comprámos noutro fornecedor\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("REJEITADA"))
                .andExpect(jsonPath("$.motivoRejeicao").value("Já comprámos noutro fornecedor"))
                .andExpect(jsonPath("$.purchaseOrderId").doesNotExist());
        mockMvc.perform(com(post("/api/po-robot/propostas/" + idMangueira + "/rejeitar"), compradorToken).contentType("application/json").content("{\"motivo\":\"outra vez\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(com(post("/api/po-robot/propostas/" + idMangueira + "/submeter"), compradorToken)).andExpect(status().isConflict());

        // A actividade conta a história, com o nome de quem decidiu.
        JsonNode actividades = json(com(get("/api/po-robot/actividades"), compradorToken), 200);
        assertThat(actividades.findValuesAsText("tipo")).contains("PROPOSTA_AJUSTADA", "PROPOSTA_SUBMETIDA", "PROPOSTA_REJEITADA");
        for (JsonNode a : actividades) {
            if (a.get("tipo").asText().equals("PROPOSTA_SUBMETIDA")) {
                assertThat(a.get("autor").get("name").asText()).isNotBlank();
                assertThat(a.get("descricao").asText()).contains("aprovação");
            }
        }
    }

    @Test
    void duplicarARegraDeUmProdutoDa409EOutrosErrosDeValidacaoDao422() throws Exception {
        ativarAddon();
        criarRegra(compradorToken, valvulaId, 2, null);
        Map<String, Object> corpo = new HashMap<>(Map.of("productId", valvulaId, "mediaOrigem", "MANUAL", "mediaMensal", 10, "periodicidade", "MENSAL"));
        mockMvc.perform(com(post("/api/po-robot/regras"), compradorToken).contentType("application/json").content(objectMapper.writeValueAsString(corpo)))
                .andExpect(status().isConflict());

        corpo.put("productId", mangueiraId);
        corpo.put("mediaMensal", 0);
        mockMvc.perform(com(post("/api/po-robot/regras"), compradorToken).contentType("application/json").content(objectMapper.writeValueAsString(corpo)))
                .andExpect(status().isUnprocessableEntity());
        corpo.put("mediaMensal", 10);
        corpo.put("mediaOrigem", "ERP");
        mockMvc.perform(com(post("/api/po-robot/regras"), compradorToken).contentType("application/json").content(objectMapper.writeValueAsString(corpo)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.message").value(containsString("IA ou MANUAL")));
        corpo.put("mediaOrigem", "MANUAL");
        corpo.put("prazoEntregaDias", 0);
        mockMvc.perform(com(post("/api/po-robot/regras"), compradorToken).contentType("application/json").content(objectMapper.writeValueAsString(corpo)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void removerARegraMantemPropostasEPrevisoesNoHistorico() throws Exception {
        ativarAddon();
        String regra = criarRegra(compradorToken, valvulaId, 2, null);
        correrORobot();

        mockMvc.perform(com(delete("/api/po-robot/regras/" + regra), compradorToken)).andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();

        JsonNode propostas = json(com(get("/api/po-robot/propostas"), compradorToken), 200);
        assertThat(propostas).as("as propostas mantêm-se no histórico").hasSize(1);
        assertThat(propostas.get(0).get("regraId").isNull()).isTrue();
        assertThat(propostas.get(0).get("estado").asText()).isEqualTo("PENDENTE_REVISAO");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM po_robo_previsoes WHERE company_id = ?", Integer.class, petroangolaId)).isEqualTo(1);
        json(com(get("/api/po-robot/previsoes"), compradorToken), 200).isEmpty();
        assertThat(json(com(get("/api/po-robot/previsoes"), compradorToken), 200)).as("previsões de regras removidas não se listam").isEmpty();
        assertThat(json(com(get("/api/po-robot/actividades"), compradorToken), 200).findValuesAsText("tipo")).contains("REGRA_REMOVIDA");
    }

    @Test
    void activarEDesactivarUmaRegraApareceNaActividade() throws Exception {
        ativarAddon();
        String regra = criarRegra(compradorToken, valvulaId, 2, null);
        mockMvc.perform(com(put("/api/po-robot/regras/" + regra), compradorToken).contentType("application/json").content("{\"ativo\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ativo").value(false)).andExpect(jsonPath("$.product.name").isNotEmpty());
        mockMvc.perform(com(put("/api/po-robot/regras/" + regra), compradorToken).contentType("application/json").content("{\"ativo\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(com(put("/api/po-robot/regras/" + regra), compradorToken).contentType("application/json").content("{\"mediaMensal\":50}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mediaMensal").value(50));
        entityManager.flush();
        entityManager.clear();

        var tipos = json(com(get("/api/po-robot/actividades"), compradorToken), 200).findValuesAsText("tipo");
        assertThat(tipos).contains("REGRA_CRIADA", "REGRA_DESACTIVADA", "REGRA_ACTIVADA", "REGRA_ALTERADA");
    }

    @Test
    void mediaSugeridaTrazOsCamposDoContratoSemPerderOsAntigos() throws Exception {
        ativarAddon();
        mockMvc.perform(com(get("/api/po-robot/media-sugerida/" + valvulaId), compradorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(valvulaId))
                .andExpect(jsonPath("$.produtoId").value(valvulaId))
                .andExpect(jsonPath("$.mediaMensal").value(0))
                .andExpect(jsonPath("$.amostras").value(0))
                .andExpect(jsonPath("$.periodoMeses").value(12));
    }

    @Test
    void usarMetodoHttpDesconhecidoNaoAbreBrechas() throws Exception {
        // Nada de PATCH/DELETE nas propostas: o robot e as pessoas só têm os verbos do contrato.
        for (HttpMethod m : new HttpMethod[]{HttpMethod.DELETE, HttpMethod.PATCH}) {
            int estado = mockMvc.perform(com(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(m, "/api/po-robot/propostas/x"), compradorToken))
                    .andReturn().getResponse().getStatus();
            assertThat(estado).as("%s /propostas/x", m).isIn(404, 405);
        }
    }
}
