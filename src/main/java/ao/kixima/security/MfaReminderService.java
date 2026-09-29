package ao.kixima.security;

import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditLogRepository;
import ao.kixima.audit.AuditService;
import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.notification.EmailDispatchService;
import ao.kixima.notification.EmailI18n;
import ao.kixima.security.dto.EnviarLembretesResultDto;
import ao.kixima.security.dto.MfaPendingUserDto;
import ao.kixima.user.User;
import ao.kixima.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Espelha backend/src/services/mfaLembreteService.js — quem, com poder,
 * ainda não activou a 2FA, e como lhes chegar. Usado pelo botão manual do
 * Admin ({@link ao.kixima.admin.MfaReminderController}) e pelo
 * {@link MfaReminderJob} automático (M6).
 *
 * NÃO PORTA activação por terceiro — o segundo fator só vale enquanto for a
 * própria pessoa a configurá-lo, tal como no Node.
 */
@Service
public class MfaReminderService {

    private static final int INTERVALO_LEMBRETE_HORAS = 24;
    private static final List<String> ACOES_LOGIN = List.of("LOGIN_SUCESSO", "LOGIN_2FA_PEDIDO");
    private static final String ACAO_LEMBRETE = "MFA_LEMBRETE_ENVIADO";
    private static final DateTimeFormatter DATA = DateTimeFormatter.ISO_LOCAL_DATE;

    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuditService auditService;
    private final EmailDispatchService emailDispatchService;
    private final MfaPolicyService mfaPolicyService;

    public MfaReminderService(UserRepository userRepository, AuditLogRepository auditLogRepository,
                               AuditService auditService, EmailDispatchService emailDispatchService,
                               MfaPolicyService mfaPolicyService) {
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
        this.auditService = auditService;
        this.emailDispatchService = emailDispatchService;
        this.mfaPolicyService = mfaPolicyService;
    }

    private Map<String, Instant> ultimoPorAtor(List<String> actorIds, List<String> acoes) {
        if (actorIds.isEmpty()) return Map.of();
        Map<String, Instant> m = new HashMap<>();
        for (Object[] linha : auditLogRepository.ultimoPorAtor(actorIds, acoes)) {
            m.put((String) linha[0], (Instant) linha[1]);
        }
        return m;
    }

    /** As contas com poder que ainda não têm 2FA — traz o último login e o último lembrete. */
    @Transactional(readOnly = true)
    public List<MfaPendingUserDto> pendentes() {
        List<User> users = userRepository.findMfaPendentes(mfaPolicyService.rolesObrigados());
        List<String> ids = users.stream().map(User::getId).toList();

        Map<String, Instant> logins = ultimoPorAtor(ids, ACOES_LOGIN);
        Map<String, Instant> lembretes = ultimoPorAtor(ids, List.of(ACAO_LEMBRETE));

        return users.stream()
                .map(u -> new MfaPendingUserDto(u.getId(), u.getName(), u.getEmail(),
                        u.getRole() == null ? null : u.getRole().name(),
                        u.getCompany() == null ? null : u.getCompany().getName(),
                        u.getCreatedAt(), logins.get(u.getId()), lembretes.get(u.getId())))
                .toList();
    }

    private boolean podeSerLembrado(Instant ultimoLembrete) {
        if (ultimoLembrete == null) return true;
        return ChronoUnit.HOURS.between(ultimoLembrete, Instant.now()) >= INTERVALO_LEMBRETE_HORAS;
    }

    /**
     * Texto do lembrete, na língua do destinatário. O prazo entra na mensagem
     * quando existe: "é obrigatório" sem data é um pedido; com data é uma
     * consequência.
     */
    private String corpoDoLembrete(String locale) {
        Instant prazo = mfaPolicyService.mfaEnforceFrom();
        String base = EmailI18n.t(
                "A sua conta KIXIMA aprova operações com dinheiro, por isso a senha deixou de bastar. "
                        + "Falta ativar a verificação em dois passos.",
                locale);
        String como = EmailI18n.t(
                "Entre na plataforma e vá a Configurações → Segurança. Demora menos de um minuto: "
                        + "enviamos-lhe um código por email e é só confirmá-lo.",
                locale);
        if (prazo == null) return base + "\n\n" + como;

        String data = DATA.format(prazo.atZone(java.time.ZoneOffset.UTC));
        String consequencia = EmailI18n.t(
                "A partir de {data}, sem isto configurado a sua conta só dá acesso ao ecrã de ativação — "
                        + "não conseguirá aprovar ordens nem consultar o resto da plataforma.",
                locale,
                Map.of("data", data));
        return base + "\n\n" + consequencia + "\n\n" + como;
    }

    /**
     * Envia o lembrete às contas indicadas (ou a todas as pendentes). Devolve
     * o que aconteceu a cada uma — quem chama precisa de saber a quem chegou
     * e a quem não, e porquê.
     */
    @Transactional
    public EnviarLembretesResultDto enviarLembretes(List<String> userIds, Actor actor) {
        if (!emailDispatchService.configurado()) {
            throw new BusinessRuleException("O envio de email não está configurado neste servidor — o lembrete não "
                    + "chegaria a ninguém. Configure o email antes de enviar lembretes de 2FA.");
        }

        List<MfaPendingUserDto> todas = pendentes();
        List<MfaPendingUserDto> alvo = userIds == null ? todas
                : todas.stream().filter(u -> userIds.contains(u.id())).toList();

        List<EnviarLembretesResultDto.Enviado> enviados = new ArrayList<>();
        List<EnviarLembretesResultDto.Ignorado> ignorados = new ArrayList<>();

        for (MfaPendingUserDto u : alvo) {
            if (!podeSerLembrado(u.ultimoLembrete())) {
                ignorados.add(new EnviarLembretesResultDto.Ignorado(u.email(), "Já foi lembrado nas últimas 24 horas."));
                continue;
            }
            // O idioma vem do item de pendentes(), exactamente como no Node — e lá o
            // map de pendentes() não copia `locale` do utilizador, por isso `u.locale` é
            // sempre undefined e o lembrete sai em português. Mantido igual (fidelidade
            // ao comportamento actual); a tradução fica ligada para o dia em que o item
            // passar a trazer o idioma.
            String locale = null;
            emailDispatchService.dispatch(u.email(), EmailI18n.t("Falta ativar a verificação em dois passos", locale),
                    corpoDoLembrete(locale));

            Actor atorDoRegisto = new Actor(u.id(), actor == null || actor.actorName() == null ? "Sistema" : actor.actorName(),
                    null, null, null);
            Map<String, Object> detail = new HashMap<>();
            detail.put("pedidoPor", actor == null ? null : actor.actorId());
            auditService.recordSafe(new AuditService.Entry(atorDoRegisto, ACAO_LEMBRETE, "User", u.id(), u.email(), detail));

            enviados.add(new EnviarLembretesResultDto.Enviado(u.email(), u.nome()));
        }

        return new EnviarLembretesResultDto(enviados, ignorados, List.of(), alvo.size());
    }

    /**
     * Lembrete automático, à medida que o prazo se aproxima — uma vez por
     * semana enquanto está longe, todos os dias na última semana. Fora
     * disso, silêncio: insistir com quem tem dois meses pela frente ensina
     * a ignorar o remetente. Chamado por {@link MfaReminderJob}.
     */
    @Transactional
    public int lembretesAutomaticos() {
        Instant prazo = mfaPolicyService.mfaEnforceFrom();
        if (prazo == null || !emailDispatchService.configurado()) return 0;

        long dias = (long) Math.ceil((prazo.toEpochMilli() - Instant.now().toEpochMilli()) / 86_400_000.0);
        boolean semanal = dias > 7 && Instant.now().atZone(java.time.ZoneOffset.UTC).getDayOfWeek().getValue() == 1;
        boolean diario = dias <= 7;
        if (!semanal && !diario) return 0;

        Actor sistema = new Actor(null, "Sistema", null, null, null);
        return enviarLembretes(null, sistema).enviados().size();
    }
}
