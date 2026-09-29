package ao.kixima.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
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
            CompletableFuture.runAsync(() -> jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                    .get(TEMPO_LIMITE_MS, TimeUnit.MILLISECONDS);
            m.put("status", "ok");
            m.put("database", "ok");
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
}
