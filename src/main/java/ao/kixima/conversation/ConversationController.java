package ao.kixima.conversation;

import ao.kixima.catalog.UploadFilters;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.company.Company;
import ao.kixima.conversation.dto.ConversationDto;
import ao.kixima.conversation.dto.ConversationMessageDto;
import ao.kixima.conversation.dto.StartConversationRequest;
import ao.kixima.risk.RiskAlert;
import ao.kixima.risk.RiskAlertService;
import ao.kixima.risk.RiskAlertStatus;
import ao.kixima.risk.dto.ReclassifyRequest;
import ao.kixima.risk.dto.RiskAlertDto;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.RequirePermission;
import ao.kixima.security.RequireRole;
import ao.kixima.storage.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ao.kixima.security.AdminArea.SUPORTE;
import static ao.kixima.security.PersonaRole.ADMIN_SISTEMA;
import static org.springframework.http.HttpStatus.CREATED;

/**
 * Espelha backend/src/routes/conversationRoutes.js — Chat Comercial
 * (Comprador ↔ Fornecedor/Prestador) + o painel de Trust & Safety que o
 * Suporte usa para as conversas sinalizadas.
 *
 * NÃO PORTADO: `chatMessageLimiter` (precisa de Bucket4j, ver
 * SupportController); `realtimeService` (M6).
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private static final Set<String> RECLASSIFY_STATUSES =
            Set.of("ABERTO", "EM_ANALISE", "FALSO_POSITIVO", "RESOLVIDO");

    private final ConversationService conversationService;
    private final RiskAlertService riskAlertService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    public ConversationController(ConversationService conversationService, RiskAlertService riskAlertService,
                                   StorageService storageService, ObjectMapper objectMapper) {
        this.conversationService = conversationService;
        this.riskAlertService = riskAlertService;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/unread-count")
    public Map<String, Long> contarNaoLidas() {
        return Map.of("count", conversationService.contarNaoLidas(CurrentUserHolder.get()));
    }

    @GetMapping
    public List<ConversationDto> listar() {
        CurrentUser user = CurrentUserHolder.get();
        return conversationService.listarConversas(user).stream().map(resumo -> {
            Company c = resumo.counterpart();
            ConversationDto.CounterpartDto counterpart = c == null ? null
                    : new ConversationDto.CounterpartDto(c.getId(), c.getName(), c.getLogoUrl());
            ConversationMessageDto lastMessage = resumo.lastMessage() == null ? null : ConversationMessageDto.de(resumo.lastMessage());
            return ConversationDto.semExtras(resumo.conversation()).comResumo(counterpart, lastMessage);
        }).toList();
    }

    @PostMapping
    @ResponseStatus(CREATED)
    public ConversationDto iniciar(@RequestBody(required = false) StartConversationRequest body, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        String otherCompanyId = body == null ? null : body.otherCompanyId();
        String contextType = body == null ? null : body.contextType();
        String contextId = body == null ? null : body.contextId();
        Conversation conversa = conversationService.iniciar(user, otherCompanyId, contextType, contextId, req);
        return ConversationDto.semExtras(conversa);
    }

    @GetMapping("/{id}")
    public ConversationDto obter(@PathVariable String id) {
        return ConversationDto.semExtras(conversationService.conversationComAcesso(id, CurrentUserHolder.get()));
    }

    @GetMapping("/{id}/messages")
    public List<ConversationMessageDto> mensagens(@PathVariable String id) {
        return conversationService.listarMensagens(id, CurrentUserHolder.get()).stream().map(ConversationMessageDto::de).toList();
    }

    @PostMapping("/{id}/messages")
    @ResponseStatus(CREATED)
    public ConversationMessageDto enviarMensagem(@PathVariable String id,
                                                  @RequestParam(required = false) String body,
                                                  @RequestParam(required = false) MultipartFile attachment,
                                                  HttpServletRequest req) {
        String attachmentUrl = null;
        String attachmentName = null;
        if (attachment != null && !attachment.isEmpty()) {
            String tipo = attachment.getContentType();
            boolean valido = tipo != null && (tipo.matches("^image/(png|jpe?g|webp|gif)$") || tipo.equals("application/pdf"));
            if (!valido) throw new ValidationException("Documento inválido — use PDF ou imagem (PNG/JPG).");
            UploadFilters.tamanho(attachment, UploadFilters.LIMITE_DOCUMENTO); // 10MB do uploadDocuments — DEPOIS do tipo, como o fileFilter do multer
            try {
                attachmentUrl = storageService.saveFile(attachment.getBytes(), attachment.getOriginalFilename(), tipo, "conversation-msg-" + id, "chat-comercial");
            } catch (IOException e) {
                throw new IllegalStateException("Falha a ler o anexo enviado.", e);
            }
            attachmentName = attachment.getOriginalFilename();
        }
        CurrentUser user = CurrentUserHolder.get();
        ConversationMessage mensagem = conversationService.enviarMensagem(id, user, body, attachmentUrl, attachmentName, req);
        return ConversationMessageDto.de(mensagem);
    }

    @PostMapping("/{id}/read")
    public Map<String, Boolean> marcarLidas(@PathVariable String id) {
        conversationService.marcarLidas(id, CurrentUserHolder.get());
        return Map.of("ok", true);
    }

    // --- Trust & Safety — só quem gere Suporte -----------------------------------

    @GetMapping("/admin/alerts")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(SUPORTE)
    public List<RiskAlertDto> alertas(@RequestParam(required = false) String status) {
        RiskAlertStatus filtro = status != null && RECLASSIFY_STATUSES.contains(status) ? RiskAlertStatus.valueOf(status) : null;
        // `conversation` sai sempre na listagem — a null quando a conversa já não existe (Optional.empty()).
        return riskAlertService.listarAlertas(filtro).stream().map(a -> toDto(a.alerta(), Optional.ofNullable(a.conversation() == null ? null
                : new RiskAlertDto.ConversationRef(a.conversation().getId(), a.conversation().getBuyerCompanyId(),
                a.conversation().getSupplierCompanyId(), a.buyerCompanyName(), a.supplierCompanyName())))).toList();
    }

    @GetMapping("/admin/conversations/{id}")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(SUPORTE)
    public Map<String, Object> conversaSinalizada(@PathVariable String id, HttpServletRequest req) {
        CurrentUser admin = CurrentUserHolder.get();
        RiskAlertService.ConversaSinalizada r = riskAlertService.acederConversaSinalizada(id, admin, req);
        return Map.of(
                "conversation", ConversationDto.semExtras(r.conversation()),
                "messages", r.messages().stream().map(ConversationMessageDto::de).toList(),
                "alerts", r.alerts().stream().map(a -> toDto(a, null)).toList());
    }

    @PatchMapping("/admin/alerts/{id}")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(SUPORTE)
    public RiskAlertDto reclassificar(@PathVariable String id, @RequestBody ReclassifyRequest body, HttpServletRequest req) {
        if (body.status() == null || body.status().isBlank()) throw new ValidationException("Indique o novo estado do alerta.");
        if (!RECLASSIFY_STATUSES.contains(body.status())) throw new ValidationException("Estado de alerta inválido.");
        CurrentUser admin = CurrentUserHolder.get();
        RiskAlert alerta = riskAlertService.reclassificar(id, admin, RiskAlertStatus.valueOf(body.status()), body.decision(), req);
        return toDto(alerta, null);
    }

    /** {@code conversationRef} a Java null → sem a chave (linha crua do Prisma, como reclassificar/acederConversaSinalizada). */
    private RiskAlertDto toDto(RiskAlert a, Optional<RiskAlertDto.ConversationRef> conversationRef) {
        JsonNode signals = lerJsonSilencioso(a.getSignals());
        JsonNode context = lerJsonSilencioso(a.getContext());
        return new RiskAlertDto(a.getId(), a.getConversationId(), a.getMessageId(), a.getLevel().name(), a.getReason(),
                signals, context, a.getStatus().name(), a.getReviewedById(), a.getReviewedAt(), a.getDecision(),
                a.getCreatedAt(), conversationRef);
    }

    private JsonNode lerJsonSilencioso(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
