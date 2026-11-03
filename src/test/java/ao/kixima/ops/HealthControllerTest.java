package ao.kixima.ops;

import ao.kixima.ops.HealthController.Sonda;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Espelha tests/health.test.js — as sondas respondem sem sessão, e /ready confirma a base com a latência E o acesso às
 * tabelas centrais (uma ligação aceite com o utilizador sem {@code GRANT} já não passa por «pronto»).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void sondasDeSaude() throws Exception {
        mockMvc.perform(get("/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok")).andExpect(jsonPath("$.env").value("test"));
        mockMvc.perform(get("/ready")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok")).andExpect(jsonPath("$.database").value("ok"))
                .andExpect(jsonPath("$.tables").value("ok"))
                .andExpect(jsonPath("$.latencyMs").isNumber());
    }

    private static List<Sonda> todasOk() {
        return HealthController.TABELAS_CENTRAIS.stream().map(t -> new Sonda(t, true, true, true)).toList();
    }

    @Test
    void semProblemasDeAcessoDevolveNull() {
        assertThat(HealthController.problemasDeAcesso(todasOk())).isNull();
    }

    @Test
    void indicaPorTabelaOQueFalta() {
        List<Sonda> sondas = HealthController.TABELAS_CENTRAIS.stream().map(t -> switch (t) {
            case "companies" -> new Sonda(t, true, false, false);   // sem SELECT
            case "users" -> new Sonda(t, true, true, false);        // só leitura: o registo não escreveria
            case "products" -> new Sonda(t, false, false, false);   // migrações por aplicar
            default -> new Sonda(t, true, true, true);
        }).toList();
        String problema = HealthController.problemasDeAcesso(sondas);
        assertThat(problema).contains("companies: sem permissão de leitura").contains("users: sem permissão de escrita")
                .contains("products: não existe").doesNotContain("purchase_orders").doesNotContain("audit_logs");
    }

    @Test
    @SuppressWarnings("unchecked")
    void readyDa503QuandoOUtilizadorDaBaseNaoConsegueUsarAsTabelas() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(Class.class))).thenReturn(1);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(
                HealthController.TABELAS_CENTRAIS.stream().map(t -> new Sonda(t, true, false, false)).toList());
        var r = new HealthController(jdbc, new MockEnvironment()).ready();
        assertThat(r.getStatusCode().value()).isEqualTo(503);
        assertThat(r.getBody()).containsEntry("status", "degradado").containsEntry("database", "ok").containsEntry("tables", "inacessiveis");
        assertThat((String) r.getBody().get("detail")).contains("users: sem permissão de leitura");
    }

    @Test
    @SuppressWarnings("unchecked")
    void readyContinuaA200ComTudoAcessivel() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(Class.class))).thenReturn(1);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(todasOk());
        var r = new HealthController(jdbc, new MockEnvironment()).ready();
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).containsEntry("tables", "ok");
    }
}
