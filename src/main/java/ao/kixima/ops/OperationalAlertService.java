package ao.kixima.ops;

import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditLog;
import ao.kixima.audit.AuditLogRepository;
import ao.kixima.audit.AuditService;
import ao.kixima.notification.EmailDispatchService;
import ao.kixima.security.PersonaRole;
import ao.kixima.user.User;
import ao.kixima.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Espelha backend/src/services/alertaOperacionalService.js — avisa os
 * Administradores do Sistema quando um trabalho automático (cópia de
 * segurança, retenção de dados, expiração de apólices/subscrições, ...)
 * falha. O arrefecimento (1 aviso/hora por assunto) vive na AUDITORIA, não
 * em memória — sobrevive a um reinício do processo, tal como no Node.
 *
 * Falhar a avisar NUNCA parte o que se estava a fazer: qualquer erro aqui
 * é registado e engolido, nunca propagado ao chamador.
 */
@Service
public class OperationalAlertService {

    private static final Logger log = LoggerFactory.getLogger(OperationalAlertService.class);
    private static final String ACAO = "ALERTA_OPERACIONAL_ENVIADO";
    private static final int MAX_DESTINATARIOS = 20;

    private final AuditLogRepository auditLogRepository;
    private final AuditService auditService;
    private final UserRepository userRepository;
    private final EmailDispatchService emailDispatchService;
    private final int intervaloMin;
    private final String ambiente;

    public OperationalAlertService(AuditLogRepository auditLogRepository, AuditService auditService,
                                    UserRepository userRepository, EmailDispatchService emailDispatchService,
                                    @Value("${kixima.ops.alerta-intervalo-min:60}") int intervaloMin,
                                    @Value("${spring.profiles.active:dev}") String ambiente) {
        this.auditLogRepository = auditLogRepository;
        this.auditService = auditService;
        this.userRepository = userRepository;
        this.emailDispatchService = emailDispatchService;
        this.intervaloMin = intervaloMin;
        this.ambiente = ambiente;
    }

    public record Resultado(boolean enviado, String motivo, Integer destinatarios) {
        static Resultado naoEnviado(String motivo) {
            return new Resultado(false, motivo, null);
        }
    }

    @Transactional(readOnly = true)
    boolean avisadoRecentemente(String assunto, Instant agora) {
        List<AuditLog> ultimos = auditLogRepository.findByActionAndEntityRefOrderByCreatedAtDesc(ACAO, assunto, PageRequest.of(0, 1));
        if (ultimos.isEmpty()) return false;
        return agora.toEpochMilli() - ultimos.get(0).getCreatedAt().toEpochMilli() < intervaloMin * 60_000L;
    }

    @Transactional(readOnly = true)
    List<String> destinatarios() {
        return userRepository.findByRoleAndActiveTrue(PersonaRole.ADMIN_SISTEMA).stream()
                .map(User::getEmail).filter(e -> e != null && !e.isBlank()).limit(MAX_DESTINATARIOS).toList();
    }

    /**
     * Avisa que um trabalho automático falhou.
     *
     * @param assunto identificador estável do alerta (ex.: "COPIA_SEGURANCA") — é por ele que o arrefecimento é contado.
     * @param titulo  uma linha, legível por quem recebe.
     * @param detalhe o que correu mal e o que fazer a seguir.
     */
    @Transactional
    public Resultado avisarFalha(String assunto, String titulo, String detalhe) {
        Instant agora = Instant.now();
        try {
            if (avisadoRecentemente(assunto, agora)) return Resultado.naoEnviado("avisado há pouco");

            // Sem email configurado não há nada a fazer aqui — o painel de Prontidão
            // (ao.kixima.admin.ProntidaoService) é quem assinala essa configuração em falta.
            if (!emailDispatchService.configurado()) return Resultado.naoEnviado("email não configurado");

            List<String> para = destinatarios();
            if (para.isEmpty()) return Resultado.naoEnviado("nenhum Admin do Sistema ativo");

            String corpo = String.join("\n", List.of(
                    titulo, "", detalhe, "",
                    "Ambiente: " + ambiente,
                    "Momento: " + DateTimeFormatter.ISO_INSTANT.format(agora) + " UTC", "",
                    "Este aviso é enviado no máximo uma vez por hora para o mesmo assunto.",
                    "Veja Configurações e Suporte → Prontidão para produção."));

            for (String email : para) {
                emailDispatchService.dispatch(email, "KIXIMA — " + titulo, corpo);
            }

            // Registado DEPOIS de sair, não antes: gravar primeiro faria o
            // arrefecimento começar a contar por um aviso que ninguém recebeu.
            auditService.recordSafe(new AuditService.Entry(new Actor(null, "Sistema", null, null, null),
                    ACAO, "Alerta", null, assunto, Map.of("titulo", titulo, "destinatarios", para.size())));

            return new Resultado(true, null, para.size());
        } catch (Exception e) {
            // O trabalho que falhou já falhou — rebentar aqui só acrescentaria um segundo problema por cima do primeiro.
            log.error("Não foi possível avisar do problema operacional (assunto={}): {}", assunto, e.getMessage());
            return Resultado.naoEnviado(e.getMessage());
        }
    }
}
