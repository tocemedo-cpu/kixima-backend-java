package ao.kixima.security;

import ao.kixima.audit.Actor;
import ao.kixima.notification.EmailDispatchService;
import ao.kixima.security.dto.EnviarLembretesResultDto;
import ao.kixima.security.dto.MfaPendingUserDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Teste de paridade de contrato para mfaLembreteService.enviarLembretes /
 * lembretesAutomaticos, com o envio de email simulado (o perfil de testes
 * corre em modo console, que recusaria à partida): cada envio fica no
 * trilho de auditoria (MFA_LEMBRETE_ENVIADO, SOBRE a conta lembrada), e
 * ninguém é lembrado duas vezes em 24 horas.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MfaReminderServiceTest {

    private static final String ADMIN_SISTEMA_EMAIL = "admin@kixima.co.ao";
    private static final String COMPANY_ADMIN_EMAIL = "admin@petroangola.co.ao";

    @Autowired
    private MfaReminderService mfaReminderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private EmailDispatchService emailDispatchService;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void enviaUmaVezRegistaNoTrilhoENaoInsisteNas24Horas() throws Exception {
        when(emailDispatchService.configurado()).thenReturn(true);

        String adminId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", String.class, ADMIN_SISTEMA_EMAIL);
        Actor pedidoPor = new Actor(adminId, "Admin KIXIMA", "ADMIN_SISTEMA", null, "127.0.0.1");

        // Só o Company Admin, por id — o Admin do Sistema fica de fora deste pedido.
        String companyAdminId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", String.class, COMPANY_ADMIN_EMAIL);
        EnviarLembretesResultDto r = mfaReminderService.enviarLembretes(List.of(companyAdminId), pedidoPor);

        assertThat(r.total()).isEqualTo(1);
        assertThat(r.enviados()).extracting(EnviarLembretesResultDto.Enviado::email).containsExactly(COMPANY_ADMIN_EMAIL);
        assertThat(r.ignorados()).isEmpty();
        assertThat(r.falhas()).isEmpty();
        verify(emailDispatchService, times(1)).dispatch(eq(COMPANY_ADMIN_EMAIL), eq("Falta ativar a verificação em dois passos"), anyString());

        // O registo é SOBRE a conta lembrada (actorId = ela), com quem pediu no detalhe.
        entityManager.flush();
        Integer registos = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'MFA_LEMBRETE_ENVIADO' AND actor_id = ? AND entity_ref = ? "
                        + "AND actor_name = 'Admin KIXIMA' AND detail->>'pedidoPor' = ?",
                Integer.class, companyAdminId, COMPANY_ADMIN_EMAIL, adminId);
        assertThat(registos).isEqualTo(1);

        // A lista de pendentes já mostra o lembrete.
        MfaPendingUserDto companyAdmin = mfaReminderService.pendentes().stream()
                .filter(u -> COMPANY_ADMIN_EMAIL.equals(u.email())).findFirst().orElseThrow();
        assertThat(companyAdmin.ultimoLembrete()).isNotNull();

        // Repetir a todos: o Company Admin é ignorado (24h), o Admin do Sistema ainda não tinha sido lembrado.
        EnviarLembretesResultDto r2 = mfaReminderService.enviarLembretes(null, pedidoPor);
        assertThat(r2.enviados()).extracting(EnviarLembretesResultDto.Enviado::email).containsExactly(ADMIN_SISTEMA_EMAIL);
        assertThat(r2.ignorados()).extracting(EnviarLembretesResultDto.Ignorado::email).containsExactly(COMPANY_ADMIN_EMAIL);
        assertThat(r2.ignorados().get(0).motivo()).isEqualTo("Já foi lembrado nas últimas 24 horas.");
    }

    @Test
    void semPrazoDeEntradaEmVigorOAutomaticoNaoLembraNinguem() {
        when(emailDispatchService.configurado()).thenReturn(true);
        // Perfil de testes: MFA_ENFORCE_FROM vazio — a 2FA é um convite, e um convite repetido por email é spam.
        assertThat(mfaReminderService.lembretesAutomaticos()).isZero();
        verify(emailDispatchService, times(0)).dispatch(anyString(), anyString(), anyString());
    }
}
