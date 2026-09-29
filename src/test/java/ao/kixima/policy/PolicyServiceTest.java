package ao.kixima.policy;

import ao.kixima.policy.dto.ClientPolicyDto;
import ao.kixima.policy.dto.PolicyRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de paridade de contrato para policyService.sendExpiryAlerts (ver
 * backend/src/jobs/policyExpiryJob.js) — só avisa apólices KIXIMA→Cliente
 * ainda válidas, dentro da janela de aviso (kixima.business.policy-expiry-alert-days,
 * 30 dias por omissão), e nunca reenvia (expiryAlertSentAt).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PolicyServiceTest {

    @Autowired
    private PolicyService policyService;

    @Autowired
    private KiximaToClientPolicyRepository clientPolicyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void avisoDeExpiracaoSoAsQueEstaoDentroDaJanelaENuncaReenvia() throws Exception {
        String petroangolaId = jdbcTemplate.queryForObject(
                "SELECT id FROM companies WHERE tax_id = ?", String.class, "AO-CLI-0001");
        String adminId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", String.class, "admin@kixima.co.ao");

        Instant agora = Instant.now();

        // Expira daqui a 10 dias — dentro da janela de 30 dias — recebe aviso.
        ClientPolicyDto aExpirar = policyService.emitirApoliceCliente(petroangolaId,
                new PolicyRequest("CLI-EXP-1", "Seguradora X", new BigDecimal("1000000"), "AOA",
                        agora.minus(Duration.ofDays(300)), agora.plus(Duration.ofDays(10))),
                adminId);

        // Expira daqui a 90 dias — fora da janela — não recebe aviso ainda.
        ClientPolicyDto foraDaJanela = policyService.emitirApoliceCliente(petroangolaId,
                new PolicyRequest("CLI-EXP-2", "Seguradora X", new BigDecimal("1000000"), "AOA",
                        agora.minus(Duration.ofDays(300)), agora.plus(Duration.ofDays(90))),
                adminId);

        int enviados = policyService.enviarAvisosDeExpiracao();
        assertThat(enviados).isEqualTo(1);

        assertThat(clientPolicyRepository.findById(aExpirar.id()).orElseThrow().getExpiryAlertSentAt()).isNotNull();
        assertThat(clientPolicyRepository.findById(foraDaJanela.id()).orElseThrow().getExpiryAlertSentAt()).isNull();

        // Repetir a corrida não reenvia à mesma apólice.
        int segundaCorrida = policyService.enviarAvisosDeExpiracao();
        assertThat(segundaCorrida).isZero();
    }
}
