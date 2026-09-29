package ao.kixima.retention;

import ao.kixima.retention.dto.RetentionCleanupResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de paridade de contrato para retencaoService.js: a limpeza toca só
 * as 3 categorias operacionais (notificações lidas há muito, convites
 * mortos há muito, códigos de 2FA expirados há muito) e ignora o que está
 * dentro do prazo — os prazos exatos são os da política publicada em
 * GET /api/retencao (ver RetentionControllerTest).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RetentionServiceTest {

    @Autowired
    private RetentionService retentionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void limpezaApagaSoOQueEstaForaDoPrazo() throws Exception {
        String compradorId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", String.class, "comprador@petroangola.co.ao");
        String petroangolaId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, "AO-CLI-0001");

        // Notificação lida há 200 dias (fora do prazo de 180) — apaga.
        String notifVelhaId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO notifications (id, user_id, type, channel, title, message, read_at, created_at) "
                        + "VALUES (?, ?, 'PO_APROVADA', 'IN_APP', 'x', 'x', ?, ?)",
                notifVelhaId, compradorId, java.sql.Timestamp.from(Instant.now().minus(200, ChronoUnit.DAYS)),
                java.sql.Timestamp.from(Instant.now().minus(200, ChronoUnit.DAYS)));

        // Notificação lida há só 10 dias — dentro do prazo, fica.
        String notifRecenteId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO notifications (id, user_id, type, channel, title, message, read_at, created_at) "
                        + "VALUES (?, ?, 'PO_APROVADA', 'IN_APP', 'x', 'x', ?, ?)",
                notifRecenteId, compradorId, java.sql.Timestamp.from(Instant.now().minus(10, ChronoUnit.DAYS)),
                java.sql.Timestamp.from(Instant.now().minus(10, ChronoUnit.DAYS)));

        // Notificação NUNCA lida, criada há 300 dias — fica (a política só olha para `readAt`).
        String notifPorLerId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO notifications (id, user_id, type, channel, title, message, created_at) "
                        + "VALUES (?, ?, 'PO_APROVADA', 'IN_APP', 'x', 'x', ?)",
                notifPorLerId, compradorId, java.sql.Timestamp.from(Instant.now().minus(300, ChronoUnit.DAYS)));

        // Convite EXPIRADO, morto há 100 dias (fora do prazo de 90) — apaga.
        String conviteMortoId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO employee_invites (id, company_id, name, email, role, token, status, expires_at, created_at, updated_at) "
                        + "VALUES (?, ?, 'X', ?, 'COMPRADOR', ?, 'EXPIRADO', ?, ?, ?)",
                conviteMortoId, petroangolaId, "retencao-" + conviteMortoId + "@teste.co.ao", "tok-" + conviteMortoId,
                java.sql.Timestamp.from(Instant.now().minus(100, ChronoUnit.DAYS)),
                java.sql.Timestamp.from(Instant.now().minus(100, ChronoUnit.DAYS)),
                java.sql.Timestamp.from(Instant.now().minus(100, ChronoUnit.DAYS)));

        // Convite PENDENTE, mesmo há 100 dias — nunca se apaga um convite vivo.
        String convitePendenteId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO employee_invites (id, company_id, name, email, role, token, status, expires_at, created_at, updated_at) "
                        + "VALUES (?, ?, 'X', ?, 'COMPRADOR', ?, 'PENDENTE', ?, ?, ?)",
                convitePendenteId, petroangolaId, "retencao2-" + convitePendenteId + "@teste.co.ao", "tok2-" + convitePendenteId,
                java.sql.Timestamp.from(Instant.now().plus(5, ChronoUnit.DAYS)),
                java.sql.Timestamp.from(Instant.now().minus(100, ChronoUnit.DAYS)),
                java.sql.Timestamp.from(Instant.now().minus(100, ChronoUnit.DAYS)));

        // Código de 2FA expirado há 5 dias (fora do prazo de 1 dia) — limpa.
        jdbcTemplate.update("UPDATE users SET mfa_code_hash = 'x', mfa_code_expira_em = ?, mfa_code_tentativas = 3 WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(5, ChronoUnit.DAYS)), compradorId);

        RetentionCleanupResult r = retentionService.limpar();

        assertThat(r.notificacoes()).isEqualTo(1);
        assertThat(r.convites()).isEqualTo(1);
        assertThat(r.codigos2fa()).isEqualTo(1);
        assertThat(r.total()).isEqualTo(3);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE id = ?", Integer.class, notifVelhaId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE id = ?", Integer.class, notifRecenteId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE id = ?", Integer.class, notifPorLerId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM employee_invites WHERE id = ?", Integer.class, conviteMortoId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM employee_invites WHERE id = ?", Integer.class, convitePendenteId)).isEqualTo(1);

        Boolean codigoAindaLa = jdbcTemplate.queryForObject("SELECT (mfa_code_hash IS NOT NULL) FROM users WHERE id = ?", Boolean.class, compradorId);
        assertThat(codigoAindaLa).isFalse();
    }
}
