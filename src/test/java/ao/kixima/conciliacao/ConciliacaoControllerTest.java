package ao.kixima.conciliacao;

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de paridade de contrato para conciliacaoService.js (tests/conciliacao.test.js):
 * na dúvida NÃO se dá por paga — valor a menos/a mais, moeda diferente, débito
 * e referência desconhecida ficam a aguardar pessoa; quando tudo bate a fatura
 * fica PAGA por REFERENCIA_BANCARIA; reimportar não paga duas vezes; corrigir a
 * referência à mão volta a tentar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ConciliacaoControllerTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String FINANCEIRO_EMAIL = "financeiro@petroangola.co.ao";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String REF = "KXABCD-EFGH";

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

    private Map<String, Object> linha(String idNoBanco, Object montante, String descricao, String moeda) {
        Map<String, Object> m = new HashMap<>();
        m.put("idNoBanco", idNoBanco);
        m.put("dataValor", "2026-09-20T10:00:00Z");
        m.put("montante", montante);
        m.put("descricao", descricao);
        if (moeda != null) m.put("moeda", moeda);
        return m;
    }

    private JsonNode importar(String token, List<Map<String, Object>> linhas) throws Exception {
        var res = mockMvc.perform(post("/api/conciliacao/extrato").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(objectMapper.writeValueAsString(Map.of("linhas", linhas))))
                .andExpect(status().isOk())
                .andReturn();
        entityManager.flush();
        entityManager.clear();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private String estadoFatura(String invoiceId) {
        return jdbcTemplate.queryForObject("SELECT status::text FROM invoices WHERE id = ?", String.class, invoiceId);
    }

    @Test
    void referenciaReconhecidaComoAsPessoasAEscrevem() {
        assertThat(ConciliacaoService.extrairReferencia("TRF kxabcd efgh fatura")).isEqualTo(REF);
        assertThat(ConciliacaoService.extrairReferencia("KXABCDEFGH")).isEqualTo(REF);
        assertThat(ConciliacaoService.extrairReferencia("pagamento sem nada")).isNull();
        for (int i = 0; i < 50; i++) assertThat(ConciliacaoService.gerarReferencia()).doesNotContainPattern("[OI01]").matches("KX[A-Z2-9]{4}-[A-Z2-9]{4}");
    }

    @Test
    void naDuvidaNaoSeDaPorPagaEQuandoTudoBateAFaturaFicaPaga() throws Exception {
        String financeiroToken = login(FINANCEIRO_EMAIL);
        String compradorToken = login(COMPRADOR_EMAIL);
        String invoiceId = jdbcTemplate.queryForObject("SELECT id FROM invoices WHERE reference = ?", String.class, "FAT-2026-00001"); // 2.394.000 AOA, PENDENTE
        jdbcTemplate.update("UPDATE invoices SET referencia_pagamento = ? WHERE id = ?", REF, invoiceId);

        mockMvc.perform(get("/api/conciliacao/por-resolver").header("Authorization", "Bearer " + compradorToken))
                .andExpect(status().isForbidden());

        // Valor a menos, valor a mais, moeda diferente: DIVERGENTE — fatura continua pendente.
        JsonNode r = importar(financeiroToken, List.of(
                linha("bk-1", 2393999.99, "trf " + REF, null),
                linha("bk-2", 2394000.01, "trf " + REF, null),
                linha("bk-3", 2394000, "trf " + REF, "USD")));
        assertThat(r.get("conciliadas").asInt()).isZero();
        assertThat(r.get("porResolver").asInt()).isEqualTo(3);
        assertThat(r.get("detalhes").get(0).get("estado").asText()).isEqualTo("DIVERGENTE");
        assertThat(r.get("detalhes").get(0).get("motivo").asText()).contains("Valor diferente");
        assertThat(r.get("detalhes").get(2).get("motivo").asText()).contains("Moeda");
        assertThat(estadoFatura(invoiceId)).isEqualTo("PENDENTE");

        // Um débito com referência NÃO paga nada; uma referência desconhecida fica a aguardar pessoa — e a linha fica guardada.
        r = importar(financeiroToken, List.of(
                linha("bk-4", -2394000, "trf " + REF, null),
                linha("bk-5", 500, "trf KXZZZZ-ZZZZ", null)));
        assertThat(r.get("conciliadas").asInt()).isZero();
        assertThat(r.get("detalhes").get(0).get("estado").asText()).isEqualTo("SEM_CORRESPONDENCIA");
        assertThat(r.get("detalhes").get(1).get("estado").asText()).isEqualTo("SEM_CORRESPONDENCIA");
        assertThat(jdbcTemplate.queryForObject("SELECT estado FROM linhas_extrato WHERE id_no_banco = 'bk-5'", String.class)).isEqualTo("SEM_CORRESPONDENCIA");
        assertThat(estadoFatura(invoiceId)).isEqualTo("PENDENTE");

        // Tudo bate: a fatura fica PAGA, por REFERENCIA_BANCARIA, e a PO também.
        r = importar(financeiroToken, List.of(linha("bk-6", 2394000, "Transferencia kx abcd efgh", null)));
        assertThat(r.get("importadas").asInt()).isEqualTo(1);
        assertThat(r.get("conciliadas").asInt()).isEqualTo(1);
        assertThat(estadoFatura(invoiceId)).isEqualTo("PAGA");
        Map<String, Object> pagamento = jdbcTemplate.queryForMap("SELECT canal::text AS canal, reference, status::text AS status FROM payments WHERE invoice_id = ?", invoiceId);
        assertThat(pagamento.get("canal")).isEqualTo("REFERENCIA_BANCARIA");
        assertThat(pagamento.get("reference")).isEqualTo("CONC-bk-6");
        assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM purchase_orders WHERE reference = 'PO-2026-00002'", String.class)).isEqualTo("PAGA");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'PAGAMENTO_CONCILIADO' AND entity_id = ?", Integer.class, invoiceId)).isEqualTo(1);

        // Importar o mesmo extrato duas vezes não paga a fatura duas vezes.
        r = importar(financeiroToken, List.of(linha("bk-6", 2394000, "Transferencia kx abcd efgh", null)));
        assertThat(r.get("importadas").asInt()).isZero();
        assertThat(r.get("repetidas").asInt()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payments WHERE invoice_id = ?", Integer.class, invoiceId)).isEqualTo(1);

        // Um segundo pagamento com a MESMA referência é sinalizado, não engolido.
        r = importar(financeiroToken, List.of(linha("bk-7", 2394000, "trf " + REF, null)));
        assertThat(r.get("detalhes").get(0).get("estado").asText()).isEqualTo("DIVERGENTE");
        assertThat(r.get("detalhes").get(0).get("motivo").asText()).contains("já tem pagamento");

        // O que sobra para uma pessoa — com a fatura quando há.
        mockMvc.perform(get("/api/conciliacao/por-resolver").header("Authorization", "Bearer " + financeiroToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(6))
                .andExpect(jsonPath("$.itens[?(@.idNoBanco=='bk-7')].invoice.reference").value("FAT-2026-00001"));

        // Canais: os dois de sempre, e o Multicaixa por ligar.
        mockMvc.perform(get("/api/conciliacao/canais").header("Authorization", "Bearer " + financeiroToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canais.length()").value(3))
                .andExpect(jsonPath("$.canais[2].canal").value("MULTICAIXA_EXPRESS"))
                .andExpect(jsonPath("$.canais[2].disponivel").value(false));
    }

    @Test
    void corrigirAReferenciaAMaoVoltaATentar() throws Exception {
        String financeiroToken = login(FINANCEIRO_EMAIL);
        String invoiceId = jdbcTemplate.queryForObject("SELECT id FROM invoices WHERE reference = ?", String.class, "FAT-2026-00001");
        jdbcTemplate.update("UPDATE invoices SET referencia_pagamento = ? WHERE id = ?", REF, invoiceId);

        JsonNode r = importar(financeiroToken, List.of(linha("bk-10", 2394000, "transferencia sem referencia legivel", null)));
        assertThat(r.get("detalhes").get(0).get("estado").asText()).isEqualTo("SEM_CORRESPONDENCIA");
        String linhaId = jdbcTemplate.queryForObject("SELECT id FROM linhas_extrato WHERE id_no_banco = 'bk-10'", String.class);

        mockMvc.perform(post("/api/conciliacao/" + linhaId + "/conciliar").header("Authorization", "Bearer " + financeiroToken)
                        .contentType("application/json").content(objectMapper.writeValueAsString(Map.of("referencia", REF))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONCILIADA"));
        entityManager.flush();
        entityManager.clear();
        assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM invoices WHERE id = ?", String.class, invoiceId)).isEqualTo("PAGA");

        // Já conciliada — não se reprocessa.
        mockMvc.perform(post("/api/conciliacao/" + linhaId + "/conciliar").header("Authorization", "Bearer " + financeiroToken)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(containsString("já foi conciliada")));
    }
}
