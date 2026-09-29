package ao.kixima.support;

import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditService;
import ao.kixima.catalog.UploadFilters;
import ao.kixima.common.error.ErrorResponse;
import ao.kixima.common.error.InvalidRequestException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.RequirePermission;
import ao.kixima.security.RequireRole;
import ao.kixima.storage.StorageService;
import ao.kixima.support.dto.CreateTicketRequest;
import ao.kixima.support.dto.SupportMessageDto;
import ao.kixima.support.dto.SupportTicketDto;
import ao.kixima.support.dto.TransferRequest;
import ao.kixima.support.dto.UpdateStatusRequest;
import ao.kixima.user.User;
import ao.kixima.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static ao.kixima.security.AdminArea.SUPORTE;
import static ao.kixima.security.PersonaRole.ADMIN_SISTEMA;
import static org.springframework.http.HttpStatus.CREATED;

/**
 * Espelha o troço de tickets/chat de backend/src/routes/supportRoutes.js
 * (não a página de Ajuda em si — categorias/canais/FAQ/gestão de imagens,
 * conteúdo presentational, fica para um passo dedicado, não é lógica de
 * "operações" no sentido do plano de migração).
 *
 * NÃO PORTADO NESTE MARCO: `chatMessageLimiter` (limite de taxa por
 * utilizador na rota de mensagens — precisa de Bucket4j, ainda sem
 * dependência no pom.xml); `realtimeService.emitToTicket` (M6, mesma
 * decisão de NotificationService).
 */
@RestController
@RequestMapping("/api/support")
public class SupportController {

    private static final Set<String> STATUSES = Set.of("ABERTO", "EM_ANDAMENTO", "AGUARDANDO_RESPOSTA", "RESOLVIDO", "FECHADO");

    private final SupportTicketRepository ticketRepository;
    private final SupportChatService supportChatService;
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final AuditService auditService;
    private final StorageService storageService;
    private final SupportOverviewService overviewService;

    public SupportController(SupportTicketRepository ticketRepository, SupportChatService supportChatService,
                              UserRepository userRepository, CompanyRepository companyRepository,
                              AuditService auditService, StorageService storageService, SupportOverviewService overviewService) {
        this.ticketRepository = ticketRepository;
        this.supportChatService = supportChatService;
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.auditService = auditService;
        this.storageService = storageService;
        this.overviewService = overviewService;
    }

    // --- Página de Ajuda e imagens dos locais (Lacunas D.6) ----------------------

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return overviewService.overview(CurrentUserHolder.get());
    }

    @GetMapping("/admin/overview")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(SUPORTE)
    public Map<String, Object> adminOverview() {
        return overviewService.adminOverview();
    }

    /** uploadSlotImage — local inválido é 404, sem ficheiro é 400, formato não suportado 422, grande demais 413. */
    private ResponseEntity<?> uploadSlotImage(String key, MultipartFile image) {
        if (!SupportOverviewService.slotValido(key)) {
            return ResponseEntity.status(404).body(ErrorResponse.of("NOT_FOUND", "Local de imagem inválido."));
        }
        if (image == null || image.isEmpty()) {
            return ResponseEntity.badRequest().body(ErrorResponse.of("NO_FILE", "Nenhuma imagem enviada."));
        }
        UploadFilters.imagem(image, UploadFilters.LIMITE_IMAGEM);
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (java.io.IOException e) {
            throw new ValidationException("Não foi possível ler o ficheiro enviado.");
        }
        String nome = image.getOriginalFilename() == null || image.getOriginalFilename().isBlank() ? key + ".jpg" : image.getOriginalFilename();
        String imageUrl = storageService.saveFile(bytes, nome, image.getContentType(), "support-" + key, "support");
        return ResponseEntity.ok(overviewService.guardarImagem(key, imageUrl));
    }

    @PostMapping("/images/{key}")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(SUPORTE)
    public ResponseEntity<?> uploadImagem(@PathVariable String key, @RequestParam(value = "image", required = false) MultipartFile image) {
        return uploadSlotImage(key, image);
    }

    @DeleteMapping("/images/{key}")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(SUPORTE)
    public Map<String, String> removerImagem(@PathVariable String key) {
        overviewService.removerImagem(key);
        return Map.of("key", key);
    }

    @PostMapping("/categories/{key}/image")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(SUPORTE)
    public ResponseEntity<?> uploadImagemDeCategoria(@PathVariable String key, @RequestParam(value = "image", required = false) MultipartFile image) {
        return uploadSlotImage(key, image);
    }

    // --- Pedido do próprio utilizador ------------------------------------------

    @GetMapping("/tickets")
    public List<SupportTicketDto> meusTickets() {
        CurrentUser user = CurrentUserHolder.get();
        return ticketRepository.findByUserIdOrderByCreatedAtDesc(user.id(), PageRequest.of(0, 20)).stream()
                .map(SupportTicketDto::semExtras).toList();
    }

    /**
     * A rota do Node não passa pelo zod: valida à mão e responde 400
     * {@code INVALID} (não 422). O corpo pode nem vir ({@code req.body?.subject}).
     */
    @PostMapping("/tickets")
    @ResponseStatus(CREATED)
    public SupportTicketDto criarTicket(@RequestBody(required = false) CreateTicketRequest body) {
        CurrentUser user = CurrentUserHolder.get();
        String subject = corta(body == null ? null : body.subject(), 160);
        // `String(category || 'Geral').slice(0, 60)` — sem trim, ao contrário do assunto/mensagem.
        String category = body == null || body.category() == null || body.category().isEmpty() ? "Geral" : semTrim(body.category(), 60);
        String message = corta(body == null ? null : body.message(), 2000);
        if (subject.isEmpty() || message.isEmpty()) {
            throw new InvalidRequestException("Assunto e mensagem são obrigatórios.");
        }
        int ano = Instant.now().atZone(java.time.ZoneOffset.UTC).getYear();
        long count = ticketRepository.count();
        String reference = "SUP-" + ano + "-" + String.format("%05d", count + 1);
        SupportTicket ticket = new SupportTicket(UUID.randomUUID().toString(), reference, user.id(), user.companyId(),
                subject, category, message, Instant.now());
        ticketRepository.save(ticket);
        return SupportTicketDto.semExtras(ticket);
    }

    private static String corta(String s, int max) {
        return semTrim(s == null ? "" : s.strip(), max);
    }

    private static String semTrim(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    // --- Administração (ADMIN_SISTEMA, área Suporte) ---------------------------

    @GetMapping("/admin/tickets")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(SUPORTE)
    public List<SupportTicketDto> ticketsAdmin(@RequestParam(required = false) String status) {
        List<SupportTicket> tickets;
        if (status != null && STATUSES.contains(status)) {
            tickets = ticketRepository.findByStatusOrderByCreatedAtDesc(SupportStatus.valueOf(status), PageRequest.of(0, 100)).getContent();
        } else {
            tickets = ticketRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 100)).getContent();
        }

        Set<String> userIds = tickets.stream().map(SupportTicket::getUserId).collect(Collectors.toSet());
        Set<String> companyIds = tickets.stream().map(SupportTicket::getCompanyId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<String, User> uById = userRepository.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, u -> u));
        Map<String, Company> cById = companyRepository.findAllById(companyIds).stream().collect(Collectors.toMap(Company::getId, c -> c));

        return tickets.stream().map(t -> {
            SupportTicketDto dto = SupportTicketDto.semExtras(t);
            User u = uById.get(t.getUserId());
            SupportTicketDto.UserRef userRef = u == null ? null : new SupportTicketDto.UserRef(u.getName(), u.getEmail());
            String company = t.getCompanyId() == null || cById.get(t.getCompanyId()) == null ? null : cById.get(t.getCompanyId()).getName();
            return dto.comUserECompany(userRef, company);
        }).toList();
    }

    @PatchMapping("/tickets/{id}")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(SUPORTE)
    public SupportTicketDto atualizarEstado(@PathVariable String id, @RequestBody(required = false) UpdateStatusRequest body) {
        // 400 INVALID escrito à mão no Node, não é validação zod (422).
        if (body == null || body.status() == null || !STATUSES.contains(body.status())) {
            throw new InvalidRequestException("Estado inválido.");
        }
        SupportTicket ticket = ticketRepository.findById(id).orElseThrow(() -> new NotFoundException("Pedido de suporte"));
        ticket.setStatus(SupportStatus.valueOf(body.status()));
        return SupportTicketDto.semExtras(ticket);
    }

    // --- Chat de Suporte ---------------------------------------------------------

    @GetMapping("/unread-count")
    public Map<String, Long> contarNaoLidas() {
        return Map.of("count", supportChatService.contarNaoLidas(CurrentUserHolder.get()));
    }

    @GetMapping("/tickets/{id}")
    public SupportTicketDto ticket(@PathVariable String id) {
        SupportTicket ticket = supportChatService.ticketComAcesso(id, CurrentUserHolder.get());
        return SupportTicketDto.semExtras(ticket).comStatusLabel(SupportChatService.LABEL_ESTADO.get(ticket.getStatus()));
    }

    @GetMapping("/tickets/{id}/messages")
    public List<SupportMessageDto> mensagens(@PathVariable String id) {
        return supportChatService.listarMensagens(id, CurrentUserHolder.get()).stream().map(SupportMessageDto::de).toList();
    }

    @PostMapping("/tickets/{id}/messages")
    @ResponseStatus(CREATED)
    public SupportMessageDto enviarMensagem(@PathVariable String id,
                                             @RequestParam(required = false) String body,
                                             @RequestParam(required = false) MultipartFile attachment) {
        String attachmentUrl = null;
        String attachmentName = null;
        if (attachment != null && !attachment.isEmpty()) {
            String tipo = attachment.getContentType();
            boolean valido = tipo != null && (tipo.matches("^image/(png|jpe?g|webp|gif)$") || tipo.equals("application/pdf"));
            if (!valido) throw new ValidationException("Documento inválido — use PDF ou imagem (PNG/JPG).");
            UploadFilters.tamanho(attachment, UploadFilters.LIMITE_DOCUMENTO); // 10MB do uploadDocuments — DEPOIS do tipo, como o fileFilter do multer
            try {
                attachmentUrl = storageService.saveFile(attachment.getBytes(), attachment.getOriginalFilename(), tipo, "support-msg-" + id, "support-chat");
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Falha a ler o anexo enviado.", e);
            }
            attachmentName = attachment.getOriginalFilename();
        }
        SupportMessage mensagem = supportChatService.enviarMensagem(id, CurrentUserHolder.get(), body, attachmentUrl, attachmentName);
        return SupportMessageDto.de(mensagem);
    }

    @PostMapping("/tickets/{id}/read")
    public Map<String, Boolean> marcarLidas(@PathVariable String id) {
        supportChatService.marcarLidas(id, CurrentUserHolder.get());
        return Map.of("ok", true);
    }

    // --- Painel do agente --------------------------------------------------------

    @GetMapping("/admin/queue")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(SUPORTE)
    public List<SupportTicketDto> fila() {
        return supportChatService.listarFila().stream().map(SupportTicketDto::semExtras).toList();
    }

    @GetMapping("/admin/my-tickets")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(SUPORTE)
    public List<SupportTicketDto> meusAtendimentos() {
        CurrentUser user = CurrentUserHolder.get();
        return supportChatService.listarMeusAtendimentos(user.id()).stream().map(SupportTicketDto::semExtras).toList();
    }

    @PostMapping("/admin/tickets/{id}/assume")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(SUPORTE)
    public SupportTicketDto assumir(@PathVariable String id, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        SupportTicket ticket = supportChatService.assumir(id, user);
        registarAuditoria(user, req, "SUPORTE_TICKET_ASSUMIDO", ticket, null);
        return SupportTicketDto.semExtras(ticket);
    }

    @PostMapping("/admin/tickets/{id}/transfer")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(SUPORTE)
    public SupportTicketDto transferir(@PathVariable String id, @RequestBody(required = false) TransferRequest body, HttpServletRequest req) {
        // `String(req.body?.toUserId || '')` vazio → 400 INVALID no Node.
        if (body == null || body.toUserId() == null || body.toUserId().isEmpty()) {
            throw new InvalidRequestException("Indique o assessor de destino.");
        }
        CurrentUser user = CurrentUserHolder.get();
        String de = ticketRepository.findById(id).map(SupportTicket::getAssignedToId).orElse(null);
        SupportTicket ticket = supportChatService.transferir(id, user, body.toUserId());
        Map<String, Object> detail = new HashMap<>(auditService.contextoFrom(req));
        detail.put("de", de);
        detail.put("para", body.toUserId());
        registarAuditoria(user, req, "SUPORTE_TICKET_TRANSFERIDO", ticket, detail);
        return SupportTicketDto.semExtras(ticket);
    }

    @PostMapping("/admin/tickets/{id}/resolve")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(SUPORTE)
    public SupportTicketDto resolver(@PathVariable String id, HttpServletRequest req) {
        return mudarEstadoComAuditoria(id, SupportStatus.RESOLVIDO, "SUPORTE_TICKET_RESOLVIDO", req);
    }

    @PostMapping("/admin/tickets/{id}/close")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(SUPORTE)
    public SupportTicketDto fechar(@PathVariable String id, HttpServletRequest req) {
        return mudarEstadoComAuditoria(id, SupportStatus.FECHADO, "SUPORTE_TICKET_FECHADO", req);
    }

    @PostMapping("/admin/tickets/{id}/reopen")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(SUPORTE)
    public SupportTicketDto reabrir(@PathVariable String id, HttpServletRequest req) {
        return mudarEstadoComAuditoria(id, SupportStatus.EM_ANDAMENTO, "SUPORTE_TICKET_REABERTO", req);
    }

    private SupportTicketDto mudarEstadoComAuditoria(String id, SupportStatus novoEstado, String acao, HttpServletRequest req) {
        CurrentUser user = CurrentUserHolder.get();
        String de = ticketRepository.findById(id).map(t -> t.getStatus().name()).orElse(null);
        SupportTicket ticket = supportChatService.mudarEstado(id, user, novoEstado);
        Map<String, Object> detail = new HashMap<>(auditService.contextoFrom(req));
        detail.put("de", de);
        detail.put("para", novoEstado.name());
        registarAuditoria(user, req, acao, ticket, detail);
        return SupportTicketDto.semExtras(ticket);
    }

    private void registarAuditoria(CurrentUser user, HttpServletRequest req, String action, SupportTicket ticket, Map<String, Object> detail) {
        Actor actor = auditService.actorFrom(user, req);
        Object detalhe = detail == null ? auditService.contextoFrom(req) : detail;
        auditService.recordSafe(new AuditService.Entry(actor, action, "SupportTicket", ticket.getId(), ticket.getReference(), detalhe));
    }

    @GetMapping("/admin/agents")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(SUPORTE)
    public List<Map<String, String>> agentes() {
        return userRepository.findByRoleAndActiveTrue(ADMIN_SISTEMA).stream()
                .filter(u -> SupportChatService.podeGerirSuporte(u.getRole(), u.getAdminAreas()))
                .map(u -> {
                    Map<String, String> m = new HashMap<>();
                    m.put("id", u.getId());
                    m.put("name", u.getName());
                    m.put("email", u.getEmail());
                    return m;
                }).toList();
    }
}
