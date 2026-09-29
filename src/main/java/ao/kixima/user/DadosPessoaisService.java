package ao.kixima.user;

import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditLog;
import ao.kixima.audit.AuditLogRepository;
import ao.kixima.audit.AuditService;
import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.company.Company;
import ao.kixima.notification.Notification;
import ao.kixima.notification.NotificationRepository;
import ao.kixima.payment.Payment;
import ao.kixima.payment.PaymentRepository;
import ao.kixima.po.PurchaseOrder;
import ao.kixima.po.PurchaseOrderRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Espelha dadosPessoaisService.js — direitos do titular dos dados (Lei n.º
 * 22/11): ACEDER (um documento com tudo) e ELIMINAR. A eliminação é uma
 * ANONIMIZAÇÃO: o trilho de auditoria financeira tem de sobreviver por
 * obrigação legal; o que identifica a pessoa é substituído e a conta fechada.
 */
@Service
public class DadosPessoaisService {

    static final String NOME_ANONIMO = "Utilizador anonimizado";

    public record Conta(String id, String name, String email, String role, boolean active, String avatarUrl, String locale,
                        BigDecimal approvalCap, Instant createdAt, Instant updatedAt, Instant termsAcceptedAt, Instant totpEnabledAt,
                        CompanyRef company) {
    }

    public record CompanyRef(String id, String name, String taxId, String type) {
    }

    public record OrdemCriada(String reference, String status, BigDecimal totalAmount, String currency, Instant createdAt) {
    }

    public record OrdemAprovada(String reference, String status, Instant approvedAt) {
    }

    public record PagamentoAutorizado(BigDecimal amount, String currency, String status, Instant processedAt) {
    }

    public record Acao(String action, String entityType, String entityRef, String ip, Instant createdAt) {
    }

    public record Aviso(String type, String title, String message, Instant readAt, Instant createdAt) {
    }

    public record Atividade(List<OrdemCriada> ordensCriadas, List<OrdemAprovada> ordensAprovadas,
                            List<PagamentoAutorizado> pagamentosAutorizados, List<Acao> registoDeAcoes, List<Aviso> notificacoesRecebidas) {
    }

    public record Totais(int ordensCriadas, int ordensAprovadas, int pagamentosAutorizados, int registoDeAcoes, int notificacoesRecebidas) {
    }

    public record Documento(Instant geradoEm, String aviso, Conta conta, Atividade atividade, Totais totais) {
    }

    public record UtilizadorAnonimo(String id, String name, String email, boolean active) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ResultadoAnonimizacao(UtilizadorAnonimo utilizador, int registosDeAuditoriaPreservados, int notificacoesEliminadas,
                                        String motivo, Instant anonimizadoEm, String nota) {
    }

    private final UserRepository userRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PaymentRepository paymentRepository;
    private final AuditLogRepository auditLogRepository;
    private final NotificationRepository notificationRepository;
    private final AuditService auditService;

    public DadosPessoaisService(UserRepository userRepository, PurchaseOrderRepository purchaseOrderRepository,
                                PaymentRepository paymentRepository, AuditLogRepository auditLogRepository,
                                NotificationRepository notificationRepository, AuditService auditService) {
        this.userRepository = userRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.paymentRepository = paymentRepository;
        this.auditLogRepository = auditLogRepository;
        this.notificationRepository = notificationRepository;
        this.auditService = auditService;
    }

    /** Tudo o que a plataforma sabe sobre uma pessoa, num único documento. */
    @Transactional(readOnly = true)
    public Documento exportar(String userId) {
        User u = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("Utilizador"));
        Company c = u.getCompany();
        Conta conta = new Conta(u.getId(), u.getName(), u.getEmail(), u.getRole().name(), u.isActive(), u.getAvatarUrl(), u.getLocale(),
                u.getApprovalCap(), u.getCreatedAt(), u.getUpdatedAt(), u.getTermsAcceptedAt(), u.getTotpEnabledAt(),
                c == null ? null : new CompanyRef(c.getId(), c.getName(), c.getTaxId(), c.getType().name()));

        List<OrdemCriada> ordensCriadas = purchaseOrderRepository.findByCreatedByIdOrderByCreatedAtDesc(userId).stream()
                .map(po -> new OrdemCriada(po.getReference(), po.getStatus().name(), po.getTotalAmount(), po.getCurrency(), po.getCreatedAt())).toList();
        List<OrdemAprovada> ordensAprovadas = purchaseOrderRepository.findByApprovedByIdOrderByApprovedAtDesc(userId).stream()
                .map(po -> new OrdemAprovada(po.getReference(), po.getStatus().name(), po.getApprovedAt())).toList();
        List<PagamentoAutorizado> pagamentos = paymentRepository.findByProcessedByIdOrderByProcessedAtDesc(userId).stream()
                .map(p -> new PagamentoAutorizado(p.getAmount(), p.getCurrency(), p.getStatus().name(), p.getProcessedAt())).toList();
        List<Acao> acoes = auditLogRepository.findByActorIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 1000)).stream()
                .map(a -> new Acao(a.getAction(), a.getEntityType(), a.getEntityRef(), a.getIp(), a.getCreatedAt())).toList();
        List<Aviso> notificacoes = notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 1000)).stream()
                .map(n -> new Aviso(n.getType().name(), n.getTitle(), n.getMessage(), n.getReadAt(), n.getCreatedAt())).toList();

        return new Documento(Instant.now(), "Documento com os dados pessoais associados a esta conta na plataforma KIXIMA.", conta,
                new Atividade(ordensCriadas, ordensAprovadas, pagamentos, acoes, notificacoes),
                new Totais(ordensCriadas.size(), ordensAprovadas.size(), pagamentos.size(), acoes.size(), notificacoes.size()));
    }

    /** Marca que identifica uma conta anonimizada, sem revelar quem era. */
    static String marcaAnonima(String userId) {
        return "anonimizado-" + userId.substring(0, Math.min(8, userId.length()));
    }

    /**
     * O que DESAPARECE: nome, email, foto, idioma, segredo de 2FA, e o nome do
     * ator em todos os registos de auditoria. O que FICA: ordens, faturas,
     * pagamentos e o próprio trilho — sem nome, com a ligação intacta.
     */
    @Transactional
    public ResultadoAnonimizacao anonimizar(String userId, String motivo) {
        User u = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("Utilizador"));
        if (u.getEmail().startsWith("anonimizado-")) throw new BusinessRuleException("Esta conta já foi anonimizada.");

        // 1. A conta perde tudo o que identifica a pessoa e deixa de poder entrar.
        u.setName(NOME_ANONIMO);
        u.setEmail(marcaAnonima(userId) + "@anonimo.kixima");
        u.setAvatarUrl(null);
        u.setLocale(null);
        u.setTotpSecret(null);
        u.setTotpEnabledAt(null);
        u.setActive(false);
        u.setTokenVersion(u.getTokenVersion() + 1); // invalida qualquer sessão ainda aberta
        userRepository.saveAndFlush(u);

        // 2. O trilho mantém-se — é obrigação legal — mas sem o nome da pessoa.
        int trilho = auditLogRepository.anonimizarAtor(userId, NOME_ANONIMO);
        // 3. As notificações são correspondência pessoal: essas apagam-se.
        int avisos = notificationRepository.deleteByUserId(userId);

        // 4. O registo de que ISTO aconteceu, escrito AQUI DENTRO e já sem nome.
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("registosPreservados", trilho);
        detail.put("motivo", motivo == null || motivo.isBlank() ? null : motivo);
        auditService.record(new AuditService.Entry(new Actor(userId, NOME_ANONIMO, u.getRole().name(), u.getCompanyId(), null),
                "DADOS_PESSOAIS_ANONIMIZADOS", "User", userId, null, detail));

        return new ResultadoAnonimizacao(new UtilizadorAnonimo(u.getId(), u.getName(), u.getEmail(), u.isActive()), trilho, avisos,
                motivo == null || motivo.isBlank() ? null : motivo, Instant.now(),
                "As ordens, faturas e pagamentos foram preservados sem identificação do titular, "
                        + "por obrigação legal de conservação contabilística.");
    }
}
