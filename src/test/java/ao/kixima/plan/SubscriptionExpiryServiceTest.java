package ao.kixima.plan;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de paridade de contrato para o troço de assinaturaService.js que o
 * SubscriptionExpiryJob usa — enviarAvisosDeExpiracao: avisa por patamar
 * (D30/D7/D3/D1/D0/GRACE_INICIO/GRACE_META), só quando o patamar SOBE, e
 * nunca avisa quem já está RESTRITA (fora do período de tolerância).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SubscriptionExpiryServiceTest {

    @Autowired
    private SubscriptionExpiryService subscriptionExpiryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void avisaPorPatamarSoQuandoSobeENuncaAsRestritas() throws Exception {
        String petroangolaId = jdbcTemplate.queryForObject(
                "SELECT id FROM companies WHERE tax_id = ?", String.class, "AO-CLI-0001");
        String kiandaId = jdbcTemplate.queryForObject(
                "SELECT id FROM companies WHERE tax_id = ?", String.class, "AO-FOR-0001");

        Instant agora = Instant.now();

        // petroangola vence daqui a 5 dias — cai no patamar D7 (<=7, >3).
        jdbcTemplate.update("UPDATE companies SET plano_valido_ate = ?, ultimo_aviso_subscricao_tier = NULL WHERE id = ?",
                java.sql.Timestamp.from(agora.plus(Duration.ofDays(5))), petroangolaId);

        // kianda venceu há 10 dias — além do período de tolerância (7 dias) — RESTRITA, nunca avisada.
        jdbcTemplate.update("UPDATE companies SET plano_valido_ate = ?, ultimo_aviso_subscricao_tier = NULL WHERE id = ?",
                java.sql.Timestamp.from(agora.minus(Duration.ofDays(10))), kiandaId);
        entityManager.clear();

        int primeiraCorrida = subscriptionExpiryService.enviarAvisosDeExpiracao();
        assertThat(primeiraCorrida).isEqualTo(1);
        entityManager.flush();

        String tierPetroangola = jdbcTemplate.queryForObject(
                "SELECT ultimo_aviso_subscricao_tier FROM companies WHERE id = ?", String.class, petroangolaId);
        assertThat(tierPetroangola).isEqualTo("D7");

        String tierKianda = jdbcTemplate.queryForObject(
                "SELECT ultimo_aviso_subscricao_tier FROM companies WHERE id = ?", String.class, kiandaId);
        assertThat(tierKianda).isNull();

        // Repetir no mesmo dia, sem o patamar subir — não reenvia.
        int segundaCorrida = subscriptionExpiryService.enviarAvisosDeExpiracao();
        assertThat(segundaCorrida).isZero();
        entityManager.flush();

        // Passa a vencer amanhã (patamar D1, mais urgente que D7) — sobe, avisa de novo.
        jdbcTemplate.update("UPDATE companies SET plano_valido_ate = ? WHERE id = ?",
                java.sql.Timestamp.from(agora.plus(ChronoUnit.DAYS.getDuration())), petroangolaId);
        entityManager.clear();
        int terceiraCorrida = subscriptionExpiryService.enviarAvisosDeExpiracao();
        assertThat(terceiraCorrida).isEqualTo(1);
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT ultimo_aviso_subscricao_tier FROM companies WHERE id = ?", String.class, petroangolaId))
                .isEqualTo("D1");
    }
}
