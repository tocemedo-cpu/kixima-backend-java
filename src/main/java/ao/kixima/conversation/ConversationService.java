package ao.kixima.conversation;

import ao.kixima.audit.AuditService;
import ao.kixima.catalog.Product;
import ao.kixima.catalog.ProductRepository;
import ao.kixima.common.error.ConflictException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.company.CompanyType;
import ao.kixima.contract.Contract;
import ao.kixima.contract.ContractRepository;
import ao.kixima.notification.NotificationChannel;
import ao.kixima.notification.NotificationService;
import ao.kixima.notification.NotificationType;
import ao.kixima.po.PurchaseOrder;
import ao.kixima.conversation.dto.ConversationMessageDto;
import ao.kixima.po.PurchaseOrderRepository;
import ao.kixima.quote.QuoteRequest;
import ao.kixima.quote.QuoteRequestRepository;
import ao.kixima.realtime.RealtimeService;
import ao.kixima.risk.RiskAlert;
import ao.kixima.risk.RiskAlertRepository;
import ao.kixima.risk.RiskAlertStatus;
import ao.kixima.risk.RiskAnalysisService;
import ao.kixima.risk.RiskLevel;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.PersonaRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Espelha backend/src/services/conversationService.js — Chat Comercial,
 * Comprador ↔ Fornecedor/Prestador, isolado por empresa. Regra central: o
 * backend NUNCA confia nos ids que o pedido manda para decidir quem pode
 * falar com quem — {@link #conversationComAcesso} é chamada no TOPO de
 * toda a operação sobre uma conversa já existente.
 *
 * Os quatro contextos ("product", "purchase_order", "quote", "contract")
 * resolvem-se contra os domínios portados — RESOLVEDORES_DE_CONTEXTO do Node.
 *
 * Tempo real (M6): {@code realtimeService.emitToConversation} é
 * {@link RealtimeService#emitToConversation} (STOMP, depois do commit) e o
 * autorizador de {@code conversation:join} é {@link #conversationComAcesso},
 * chamado por {@link ao.kixima.realtime.RealtimeAuthInterceptor} no SUBSCRIBE.
 */
@Service
public class ConversationService {

    private static final Set<PersonaRole> PERSONAS_DE_EMPRESA =
            Set.of(PersonaRole.COMPRADOR, PersonaRole.COMPANY_ADMIN, PersonaRole.FORNECEDOR, PersonaRole.FINANCEIRO);
    private static final Set<RiskAlertStatus> ALERTA_ABERTO_STATUS = Set.of(RiskAlertStatus.ABERTO, RiskAlertStatus.EM_ANALISE);
    private static final Set<RiskLevel> ALERTA_ABERTO_NIVEIS = Set.of(RiskLevel.MEDIUM, RiskLevel.HIGH, RiskLevel.CRITICAL);

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    private final CompanyRepository companyRepository;
    private final ProductRepository productRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final QuoteRequestRepository quoteRequestRepository;
    private final ContractRepository contractRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final RiskAnalysisService riskAnalysisService;
    private final RiskAlertRepository riskAlertRepository;
    private final ObjectMapper objectMapper;
    private final RealtimeService realtimeService;

    public ConversationService(ConversationRepository conversationRepository, ConversationMessageRepository messageRepository,
                                CompanyRepository companyRepository, ProductRepository productRepository,
                                PurchaseOrderRepository purchaseOrderRepository, QuoteRequestRepository quoteRequestRepository,
                                ContractRepository contractRepository, AuditService auditService,
                                NotificationService notificationService, RiskAnalysisService riskAnalysisService,
                                RiskAlertRepository riskAlertRepository, ObjectMapper objectMapper,
                                RealtimeService realtimeService) {
        this.realtimeService = realtimeService;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.companyRepository = companyRepository;
        this.productRepository = productRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.quoteRequestRepository = quoteRequestRepository;
        this.contractRepository = contractRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.riskAnalysisService = riskAnalysisService;
        this.riskAlertRepository = riskAlertRepository;
        this.objectMapper = objectMapper;
    }

    private record ParDeEmpresas(String buyerCompanyId, String supplierCompanyId) {
    }

    private ParDeEmpresas resolverContexto(String contextType, String contextId, String requesterCompanyId) {
        return switch (contextType) {
            case "product" -> {
                Product p = productRepository.findById(contextId).orElseThrow(() -> new NotFoundException("Produto"));
                yield new ParDeEmpresas(requesterCompanyId, p.getSupplierId());
            }
            case "purchase_order" -> {
                PurchaseOrder po = purchaseOrderRepository.findById(contextId).orElseThrow(() -> new NotFoundException("Ordem de compra"));
                yield new ParDeEmpresas(po.getBuyerCompanyId(), po.getSupplierCompanyId());
            }
            case "quote" -> {
                QuoteRequest q = quoteRequestRepository.findById(contextId).orElseThrow(() -> new NotFoundException("Cotação"));
                yield new ParDeEmpresas(q.getBuyerCompanyId(), q.getSupplierCompanyId());
            }
            case "contract" -> {
                Contract c = contractRepository.findById(contextId).orElseThrow(() -> new NotFoundException("Contrato"));
                yield new ParDeEmpresas(c.getClientCompanyId(), c.getSupplierCompanyId());
            }
            default -> throw new ValidationException("Contexto inválido.");
        };
    }

    private static final Set<String> CONTEXT_TYPES = Set.of("quote", "purchase_order", "contract", "product");

    @Transactional
    public Conversation iniciar(CurrentUser user, String otherCompanyId, String contextType, String contextId, HttpServletRequest req) {
        if (user.companyId() == null) throw new ValidationException("Só utilizadores de uma empresa podem iniciar o Chat Comercial.");

        String buyerCompanyId;
        String supplierCompanyId;

        if (contextType != null && !contextType.isBlank()) {
            if (!CONTEXT_TYPES.contains(contextType) || contextId == null || contextId.isBlank()) {
                throw new ValidationException("Contexto inválido.");
            }
            ParDeEmpresas par = resolverContexto(contextType, contextId, user.companyId());
            if (!par.buyerCompanyId().equals(user.companyId()) && !par.supplierCompanyId().equals(user.companyId())) {
                throw new NotFoundException("Contexto");
            }
            buyerCompanyId = par.buyerCompanyId();
            supplierCompanyId = par.supplierCompanyId();
        } else {
            if (otherCompanyId == null || otherCompanyId.isBlank()) throw new ValidationException("Indique a empresa com quem quer falar.");
            if (otherCompanyId.equals(user.companyId())) throw new ValidationException("Não pode iniciar uma conversa com a sua própria empresa.");
            Company outra = companyRepository.findById(otherCompanyId).orElseThrow(() -> new NotFoundException("Empresa"));
            boolean euSouFornecedor = user.companyType() == CompanyType.FORNECEDOR;
            buyerCompanyId = euSouFornecedor ? otherCompanyId : user.companyId();
            supplierCompanyId = euSouFornecedor ? user.companyId() : otherCompanyId;
        }

        Conversation existente = conversationRepository
                .findFirstByBuyerCompanyIdAndSupplierCompanyIdAndContextTypeAndContextIdAndStatus(
                        buyerCompanyId, supplierCompanyId, contextType, contextId, ConversationStatus.ABERTA)
                .orElse(null);
        if (existente != null) return existente;

        Conversation conversa = new Conversation(UUID.randomUUID().toString(), buyerCompanyId, supplierCompanyId,
                contextType, contextId, user.id(), Instant.now());
        conversationRepository.save(conversa);

        Map<String, Object> detail = new java.util.LinkedHashMap<>(auditService.contextoFrom(req));
        detail.put("buyerCompanyId", buyerCompanyId);
        detail.put("supplierCompanyId", supplierCompanyId);
        detail.put("contextType", contextType);
        detail.put("contextId", contextId);
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(user, req), "CHAT_COMERCIAL_INICIADO",
                "Conversation", conversa.getId(), null, detail));

        return conversa;
    }

    public record ConversaResumo(Conversation conversation, Company counterpart, ConversationMessage lastMessage) {
    }

    @Transactional(readOnly = true)
    public List<ConversaResumo> listarConversas(CurrentUser user) {
        if (user.companyId() == null) return List.of();
        List<Conversation> conversas = conversationRepository
                .findByBuyerCompanyIdOrSupplierCompanyIdOrderByUpdatedAtDesc(user.companyId(), user.companyId());
        if (conversas.isEmpty()) return List.of();

        Set<String> outrasIds = conversas.stream()
                .map(c -> c.getBuyerCompanyId().equals(user.companyId()) ? c.getSupplierCompanyId() : c.getBuyerCompanyId())
                .collect(java.util.stream.Collectors.toSet());
        Map<String, Company> porId = companyRepository.findAllById(outrasIds).stream()
                .collect(java.util.stream.Collectors.toMap(Company::getId, c -> c));

        return conversas.stream().map(c -> {
            Company counterpart = porId.get(c.getBuyerCompanyId().equals(user.companyId()) ? c.getSupplierCompanyId() : c.getBuyerCompanyId());
            ConversationMessage last = messageRepository.findFirstByConversationIdOrderByCreatedAtDesc(c.getId()).orElse(null);
            return new ConversaResumo(c, counterpart, last);
        }).toList();
    }

    /** SÓ participantes — nunca ADMIN_SISTEMA por omissão (ver RiskAlertService para a excepção do Suporte). 404, não 403. */
    @Transactional(readOnly = true)
    public Conversation conversationComAcesso(String conversationId, CurrentUser user) {
        Conversation conversa = conversationRepository.findById(conversationId).orElseThrow(() -> new NotFoundException("Conversa"));
        boolean participante = user.companyId() != null
                && (conversa.getBuyerCompanyId().equals(user.companyId()) || conversa.getSupplierCompanyId().equals(user.companyId()));
        if (!participante) throw new NotFoundException("Conversa");
        return conversa;
    }

    @Transactional(readOnly = true)
    public List<ConversationMessage> listarMensagens(String conversationId, CurrentUser user) {
        conversationComAcesso(conversationId, user);
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    @Transactional
    public ConversationMessage enviarMensagem(String conversationId, CurrentUser user, String body, String attachmentUrl,
                                               String attachmentName, HttpServletRequest req) {
        String texto = body == null ? "" : body.strip();
        if (texto.length() > 4000) texto = texto.substring(0, 4000);
        if (texto.isEmpty() && (attachmentUrl == null || attachmentUrl.isBlank())) {
            throw new ValidationException("Escreva uma mensagem ou anexe um ficheiro.");
        }

        Conversation conversa = conversationComAcesso(conversationId, user);
        if (conversa.getStatus() == ConversationStatus.FECHADA) throw new ConflictException("Esta conversa está fechada.");

        ConversationMessage mensagem = new ConversationMessage(UUID.randomUUID().toString(), conversationId, user.id(),
                user.companyId(), texto, attachmentUrl, attachmentName, Instant.now());
        messageRepository.save(mensagem);
        conversa.tocarAtualizacao();

        String outraCompanyId = conversa.getBuyerCompanyId().equals(user.companyId()) ? conversa.getSupplierCompanyId() : conversa.getBuyerCompanyId();
        notificationService.notifyUsersByRole(outraCompanyId, List.copyOf(PERSONAS_DE_EMPRESA), NotificationType.CHAT_COMERCIAL_MENSAGEM,
                "Nova mensagem no Chat Comercial", "Recebeu uma nova mensagem de " + user.name() + ".",
                NotificationChannel.IN_APP, "Conversation", conversationId);

        // Trust & Safety: TODA mensagem é classificada; só MEDIUM+ vira alerta. Nunca bloqueia.
        analisarRisco(conversa, mensagem, user, req);

        realtimeService.emitToConversation(conversationId, "conversation:message", ConversationMessageDto.de(mensagem));
        return mensagem;
    }

    private void analisarRisco(Conversation conversa, ConversationMessage mensagem, CurrentUser user, HttpServletRequest req) {
        boolean alertaAberto = riskAlertRepository.findFirstByConversationIdAndStatusInAndLevelInOrderByCreatedAtDesc(
                conversa.getId(), List.copyOf(ALERTA_ABERTO_STATUS), List.copyOf(ALERTA_ABERTO_NIVEIS)).isPresent();
        RiskAnalysisService.Resultado resultado = riskAnalysisService.analisar(mensagem.getBody(), alertaAberto);
        if (resultado.level() == RiskLevel.LOW) return;

        String signalsJson = escreverJsonSilencioso(resultado.signals());
        String contextJson = escreverJsonSilencioso(Map.of(
                "buyerCompanyId", conversa.getBuyerCompanyId(), "supplierCompanyId", conversa.getSupplierCompanyId(),
                "contextType", conversa.getContextType() == null ? "" : conversa.getContextType(),
                "contextId", conversa.getContextId() == null ? "" : conversa.getContextId()));

        RiskAlert alerta = new RiskAlert(UUID.randomUUID().toString(), conversa.getId(), mensagem.getId(), resultado.level(),
                resultado.reason(), signalsJson, contextJson, Instant.now());
        riskAlertRepository.save(alerta);

        Map<String, Object> detail = new java.util.LinkedHashMap<>(auditService.contextoFrom(req));
        detail.put("conversationId", conversa.getId());
        detail.put("level", resultado.level().name());
        detail.put("reason", resultado.reason());
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(user, req), "ALERTA_SEGURANCA_CRIADO",
                "RiskAlert", alerta.getId(), null, detail));
        realtimeService.emitToConversation(conversa.getId(), "conversation:risk-alert", Map.of("level", alerta.getLevel().name()));
    }

    private String escreverJsonSilencioso(Object valor) {
        try {
            return objectMapper.writeValueAsString(valor);
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public void marcarLidas(String conversationId, CurrentUser user) {
        conversationComAcesso(conversationId, user);
        messageRepository.marcarLidas(conversationId, user.companyId(), Instant.now());
    }

    @Transactional(readOnly = true)
    public long contarNaoLidas(CurrentUser user) {
        if (user.companyId() == null) return 0;
        return messageRepository.contarNaoLidas(user.companyId());
    }
}
