package ao.kixima.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Espelha as sondas de saúde de backend/src/app.js: {@code GET /health}
 * responde sem tocar na base; {@code GET /ready} confirma a base e reporta a
 * latência, e falha ao fim de poucos segundos em vez de ficar pendurado — um
 * health check que nunca responde é tão mau como um que mente.
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);
    private static final long TEMPO_LIMITE_MS = 3000;

    /** Tabelas sem as quais nenhum fluxo funciona: contas, empresas, catálogo, ordens e a auditoria que todos os fluxos gravam. */
    static final List<String> TABELAS_CENTRAIS = List.of("users", "companies", "products", "purchase_orders", "audit_logs");

    /** Uma tabela central: existe? o utilizador da base lê? escreve? (só o catálogo é consultado). */
    record Sonda(String tabela, boolean existe, boolean leitura, boolean escrita) {
    }

    private static final String SQL_SONDA = "SELECT t.name, to_regclass(t.name) IS NOT NULL AS existe, "
            + "COALESCE(has_table_privilege(to_regclass(t.name), 'SELECT'), false) AS leitura, "
            + "COALESCE(has_table_privilege(to_regclass(t.name), 'INSERT') AND has_table_privilege(to_regclass(t.name), 'UPDATE'), false) AS escrita "
            + "FROM (VALUES " + String.join(",", TABELAS_CENTRAIS.stream().map(t -> "('" + t + "')").toList()) + ") AS t(name)";

    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;

    public HealthController(JdbcTemplate jdbcTemplate, Environment environment) {
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
    }

    private String env() {
        String[] perfis = environment.getActiveProfiles();
        return perfis.length == 0 ? "development" : perfis[0];
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put("env", env());
        return m;
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        long inicio = System.currentTimeMillis();
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            List<Sonda> sondas = CompletableFuture.supplyAsync(() -> {
                jdbcTemplate.queryForObject("SELECT 1", Integer.class);
                return jdbcTemplate.query(SQL_SONDA, (rs, i) ->
                        new Sonda(rs.getString("name"), rs.getBoolean("existe"), rs.getBoolean("leitura"), rs.getBoolean("escrita")));
            }).get(TEMPO_LIMITE_MS, TimeUnit.MILLISECONDS);
            String problema = problemasDeAcesso(sondas);
            if (problema != null) {
                // A ligação funciona mas o utilizador da base não consegue usar as tabelas: não está pronto.
                log.error("Readiness: a base responde mas o utilizador da aplicação não consegue usar as tabelas: {}", problema);
                m.put("status", "degradado");
                m.put("database", "ok");
                m.put("tables", "inacessiveis");
                m.put("detail", problema);
                m.put("latencyMs", System.currentTimeMillis() - inicio);
                return ResponseEntity.status(503).body(m);
            }
            m.put("status", "ok");
            m.put("database", "ok");
            m.put("tables", "ok");
            m.put("latencyMs", System.currentTimeMillis() - inicio);
            m.put("env", env());
            return ResponseEntity.ok(m);
        } catch (Exception err) {
            String detail = err instanceof TimeoutException ? "timeout ao contactar a base de dados"
                    : (err.getCause() != null && err.getCause().getMessage() != null ? err.getCause().getMessage() : String.valueOf(err.getMessage()));
            log.error("Readiness: base de dados inacessível: {}", detail);
            m.put("status", "degradado");
            m.put("database", "inacessivel");
            m.put("detail", detail);
            m.put("latencyMs", System.currentTimeMillis() - inicio);
            return ResponseEntity.status(503).body(m);
        }
    }

    /** {@code null} se tudo bate certo; senão, o que falta por tabela (nunca nomes de utilizador nem credenciais). */
    static String problemasDeAcesso(List<Sonda> sondas) {
        List<String> falhas = new ArrayList<>();
        for (String tabela : TABELAS_CENTRAIS) {
            Sonda sonda = sondas.stream().filter(x -> x.tabela().equals(tabela)).findFirst().orElse(null);
            if (sonda == null || !sonda.existe()) falhas.add(tabela + ": não existe (migrações por aplicar?)");
            else if (!sonda.leitura()) falhas.add(tabela + ": sem permissão de leitura");
            else if (!sonda.escrita()) falhas.add(tabela + ": sem permissão de escrita");
        }
        return falhas.isEmpty() ? null : String.join("; ", falhas);
    }
}
