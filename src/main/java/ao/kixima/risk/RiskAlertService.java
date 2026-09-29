package ao.kixima.risk;

import ao.kixima.audit.AuditService;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.conversation.Conversation;
import ao.kixima.conversation.ConversationMessage;
import ao.kixima.conversation.ConversationMessageRepository;
import ao.kixima.conversation.ConversationRepository;
import ao.kixima.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Espelha backend/src/services/riskAlertService.js — o ÚNICO caminho pelo
 * qual o Suporte chega a uma conversa do Chat Comercial. Nada aqui dá ao
 * Suporte acesso a "todas as conversas comerciais": só as sinalizadas
 * (RiskAlert ativo) aparecem, e aceder a UMA delas fica sempre gravado na
 * auditoria. RBAC (ADMIN_SISTEMA + área Suporte) é aplicado ao nível do
 * controller ({@code @RequireRole}/{@code @RequirePermission}) — este
 * serviço verifica só a segunda condição, que a rota não sabe verificar
 * sozinha: que a conversa TEM MESMO um alerta.
 */
@Service
public class RiskAlertService {

    private static final Set<RiskAlertStatus> STATUS_RECLASSIFICACAO =
            Set.of(RiskAlertStatus.EM_ANALISE, RiskAlertStatus.FALSO_POSITIVO, RiskAlertStatus.RESOLVIDO);

    private static final Map<RiskAlertStatus, String> ACTION_POR_STATUS = Map.of(
            RiskAlertStatus.EM_ANALISE, "ALERTA_SEGURANCA_EM_ANALISE",
            RiskAlertStatus.FALSO_POSITIVO, "ALERTA_SEGURANCA_FALSO_POSITIVO",
            RiskAlertStatus.RESOLVIDO, "ALERTA_SEGURANCA_RESOLVIDO");

    private final RiskAlertRepository riskAlertRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    private final CompanyRepository companyRepository;
    private final AuditService auditService;

    public RiskAlertService(RiskAlertRepository riskAlertRepository, ConversationRepository conversationRepository,
                             ConversationMessageRepository messageRepository, CompanyRepository companyRepository,
                             AuditService auditService) {
        this.riskAlertRepository = riskAlertRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.companyRepository = companyRepository;
        this.auditService = auditService;
    }

    public record AlertaComConversa(RiskAlert alerta, Conversation conversation, String buyerCompanyName, String supplierCompanyName) {
    }

    @Transactional(readOnly = true)
    public List<AlertaComConversa> listarAlertas(RiskAlertStatus status) {
        List<RiskAlert> alertas = status != null
                ? riskAlertRepository.findByStatusOrderByCreatedAtDesc(status, PageRequest.of(0, 100))
                : riskAlertRepository.findByStatusInOrderByCreatedAtDesc(
                        List.of(RiskAlertStatus.ABERTO, RiskAlertStatus.EM_ANALISE), PageRequest.of(0, 100));
        if (alertas.isEmpty()) return List.of();

        Set<String> conversationIds = alertas.stream().map(RiskAlert::getConversationId).collect(java.util.stream.Collectors.toSet());
        Map<String, Conversation> convPorId = conversationRepository.findAllById(conversationIds).stream()
                .collect(java.util.stream.Collectors.toMap(Conversation::getId, c -> c));

        Set<String> companyIds = new java.util.HashSet<>();
        convPorId.values().forEach(c -> {
            companyIds.add(c.getBuyerCompanyId());
            companyIds.add(c.getSupplierCompanyId());
        });
        Map<String, String> nomePorId = companyRepository.findAllById(companyIds).stream()
                .collect(java.util.stream.Collectors.toMap(Company::getId, Company::getName));

        return alertas.stream().map(a -> {
            Conversation c = convPorId.get(a.getConversationId());
            return new AlertaComConversa(a, c, c == null ? null : nomePorId.get(c.getBuyerCompanyId()),
                    c == null ? null : nomePorId.get(c.getSupplierCompanyId()));
        }).toList();
    }

    public record ConversaSinalizada(Conversation conversation, List<ConversationMessage> messages, List<RiskAlert> alerts) {
    }

    /**
     * Acesso a UMA conversa sinalizada — exige um alerta (qualquer
     * estado, mesmo já resolvido: o histórico de uma conversa que já
     * teve alerta continua legítimo consultar). Sem alerta nenhuma vez,
     * nem o Suporte entra.
     */
    @Transactional
    public ConversaSinalizada acederConversaSinalizada(String conversationId, CurrentUser admin, HttpServletRequest req) {
        if (!riskAlertRepository.existsByConversationId(conversationId)) throw new NotFoundException("Conversa sinalizada");
        Conversation conversa = conversationRepository.findById(conversationId).orElseThrow(() -> new NotFoundException("Conversa sinalizada"));

        List<ConversationMessage> mensagens = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        List<RiskAlert> alertas = riskAlertRepository.findByConversationIdOrderByCreatedAtDesc(conversationId);

        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(admin, req), "CONVERSA_SINALIZADA_ACEDIDA",
                "Conversation", conversationId, null, auditService.contextoFrom(req)));

        return new ConversaSinalizada(conversa, mensagens, alertas);
    }

    @Transactional
    public RiskAlert reclassificar(String alertId, CurrentUser admin, RiskAlertStatus status, String decision, HttpServletRequest req) {
        if (status == null || !STATUS_RECLASSIFICACAO.contains(status)) throw new ValidationException("Estado de alerta inválido.");
        RiskAlert alerta = riskAlertRepository.findById(alertId).orElseThrow(() -> new NotFoundException("Alerta de segurança"));

        alerta.setStatus(status);
        String decisaoCortada = decision == null ? null : decision.strip();
        if (decisaoCortada != null && decisaoCortada.length() > 1000) decisaoCortada = decisaoCortada.substring(0, 1000);
        alerta.setDecision(decisaoCortada == null || decisaoCortada.isBlank() ? null : decisaoCortada);
        alerta.setReviewedById(admin.id());
        alerta.setReviewedAt(Instant.now());

        Map<String, Object> detail = new LinkedHashMap<>(auditService.contextoFrom(req));
        detail.put("conversationId", alerta.getConversationId());
        detail.put("decision", alerta.getDecision());
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(admin, req), ACTION_POR_STATUS.get(status),
                "RiskAlert", alertId, null, detail));

        return alerta;
    }
}
