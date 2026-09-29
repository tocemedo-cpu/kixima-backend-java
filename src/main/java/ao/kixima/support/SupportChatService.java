package ao.kixima.support;

import ao.kixima.common.error.ConflictException;
import ao.kixima.common.error.ForbiddenException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.notification.NotificationChannel;
import ao.kixima.notification.NotificationService;
import ao.kixima.notification.NotificationType;
import ao.kixima.realtime.RealtimeService;
import ao.kixima.security.CurrentUser;
import ao.kixima.support.dto.SupportMessageDto;
import ao.kixima.support.dto.SupportTicketDto;
import ao.kixima.security.PersonaRole;
import ao.kixima.user.User;
import ao.kixima.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ao.kixima.security.AdminArea.SUPORTE;

/**
 * Espelha backend/src/services/supportChatService.js — Chat de Suporte por
 * cima do {@link SupportTicket} já existente.
 *
 * Os 5 estados de {@link SupportStatus} não mudam de nome — são os mesmos
 * que a página de Ajuda e o painel administrativo já usam. {@link #LABEL_ESTADO}
 * é só para apresentação (nunca gravado nem comparado em código).
 *
 * Tempo real (M6): {@code realtimeService.emitToTicket} é
 * {@link RealtimeService#emitToTicket} (STOMP, depois do commit — cada
 * método aqui grava primeiro, que é a fonte da verdade) e o autorizador de
 * {@code support:join} é {@link #ticketComAcesso}, chamado por
 * {@link ao.kixima.realtime.RealtimeAuthInterceptor} no SUBSCRIBE.
 */
@Service
public class SupportChatService {

    public static final Map<SupportStatus, String> LABEL_ESTADO = Map.of(
            SupportStatus.ABERTO, "Novo",
            SupportStatus.EM_ANDAMENTO, "Em Atendimento",
            SupportStatus.AGUARDANDO_RESPOSTA, "Aguardando Cliente",
            SupportStatus.RESOLVIDO, "Resolvido",
            SupportStatus.FECHADO, "Fechado");

    private final SupportTicketRepository ticketRepository;
    private final SupportMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final RealtimeService realtimeService;

    public SupportChatService(SupportTicketRepository ticketRepository, SupportMessageRepository messageRepository,
                               UserRepository userRepository, NotificationService notificationService,
                               RealtimeService realtimeService) {
        this.ticketRepository = ticketRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.realtimeService = realtimeService;
    }

    public static boolean podeGerirSuporte(PersonaRole role, List<String> adminAreas) {
        if (role != PersonaRole.ADMIN_SISTEMA) return false;
        return adminAreas == null || adminAreas.isEmpty() || adminAreas.contains(SUPORTE);
    }

    public boolean podeGerirSuporte(CurrentUser user) {
        return podeGerirSuporte(user.role(), user.adminAreas());
    }

    /** Um cliente só vê o SEU pedido; um assessor de Suporte (ou Super Admin) vê qualquer um. */
    @Transactional(readOnly = true)
    public SupportTicket ticketComAcesso(String ticketId, CurrentUser user) {
        SupportTicket ticket = ticketRepository.findById(ticketId).orElseThrow(() -> new NotFoundException("Pedido de suporte"));
        boolean dono = ticket.getUserId().equals(user.id());
        if (!dono && !podeGerirSuporte(user)) throw new NotFoundException("Pedido de suporte");
        return ticket;
    }

    @Transactional(readOnly = true)
    public List<SupportMessage> listarMensagens(String ticketId, CurrentUser user) {
        ticketComAcesso(ticketId, user);
        return messageRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
    }

    @Transactional(readOnly = true)
    public List<SupportTicket> listarFila() {
        return ticketRepository.findByStatusAndAssignedToIdIsNullOrderByCreatedAtAsc(SupportStatus.ABERTO);
    }

    @Transactional(readOnly = true)
    public List<SupportTicket> listarMeusAtendimentos(String adminId) {
        return ticketRepository.findByAssignedToIdAndStatusInOrderByCreatedAtAsc(adminId,
                List.of(SupportStatus.EM_ANDAMENTO, SupportStatus.AGUARDANDO_RESPOSTA));
    }

    @Transactional
    public SupportTicket assumir(String ticketId, CurrentUser admin) {
        if (!podeGerirSuporte(admin)) throw new ForbiddenException("Esta ação está reservada a quem gere Suporte.");
        SupportTicket ticket = ticketRepository.findById(ticketId).orElseThrow(() -> new NotFoundException("Pedido de suporte"));
        if (ticket.getAssignedToId() != null && !ticket.getAssignedToId().equals(admin.id())) {
            throw new ConflictException("Este pedido já está a ser atendido por outra pessoa. Use \"Transferir\" se for o caso.");
        }
        ticket.setAssignedToId(admin.id());
        if (ticket.getStatus() == SupportStatus.ABERTO) ticket.setStatus(SupportStatus.EM_ANDAMENTO);
        realtimeService.emitToTicket(ticketId, "support:updated", SupportTicketDto.semExtras(ticket));
        return ticket;
    }

    @Transactional
    public SupportTicket transferir(String ticketId, CurrentUser admin, String toUserId) {
        if (!podeGerirSuporte(admin)) throw new ForbiddenException("Esta ação está reservada a quem gere Suporte.");
        SupportTicket ticket = ticketRepository.findById(ticketId).orElseThrow(() -> new NotFoundException("Pedido de suporte"));
        User destino = userRepository.findById(toUserId).orElse(null);
        if (destino == null || !destino.isActive() || !podeGerirSuporte(destino.getRole(), destino.getAdminAreas())) {
            throw new ValidationException("O destinatário tem de ser um assessor de Suporte ativo.");
        }
        String de = ticket.getAssignedToId();
        ticket.setAssignedToId(destino.getId());

        notificationService.notifyUser(destino.getId(), NotificationType.SUPORTE_MENSAGEM,
                "Pedido de suporte transferido para si", "O pedido " + ticket.getReference() + " foi transferido para si.",
                NotificationChannel.IN_APP, "SupportTicket", ticketId, null);

        realtimeService.emitToTicket(ticketId, "support:updated", SupportTicketDto.semExtras(ticket));
        return ticket;
    }

    /**
     * Envia uma mensagem — cliente ou assessor, a mesma função. Transições
     * automáticas: cliente escreve num pedido RESOLVIDO/AGUARDANDO_RESPOSTA
     * → volta para EM_ANDAMENTO; assessor escreve → o pedido fica
     * AGUARDANDO_RESPOSTA (fica implicitamente assumido, se ainda não tinha
     * dono). Um pedido FECHADO não aceita mensagens novas.
     */
    @Transactional
    public SupportMessage enviarMensagem(String ticketId, CurrentUser user, String body, String attachmentUrl, String attachmentName) {
        String texto = body == null ? "" : body.strip();
        if (texto.length() > 4000) texto = texto.substring(0, 4000);
        if (texto.isEmpty() && (attachmentUrl == null || attachmentUrl.isBlank())) {
            throw new ValidationException("Escreva uma mensagem ou anexe um ficheiro.");
        }

        SupportTicket ticket = ticketComAcesso(ticketId, user);
        if (ticket.getStatus() == SupportStatus.FECHADO) {
            throw new ConflictException("Este pedido está fechado. Reabra-o para continuar a conversa.");
        }

        boolean ehCliente = ticket.getUserId().equals(user.id());
        SupportMessage mensagem = new SupportMessage(UUID.randomUUID().toString(), ticketId, user.id(), user.role(),
                texto, attachmentUrl, attachmentName, Instant.now());
        messageRepository.save(mensagem);

        if (ehCliente) {
            if (ticket.getStatus() == SupportStatus.RESOLVIDO || ticket.getStatus() == SupportStatus.AGUARDANDO_RESPOSTA) {
                ticket.setStatus(SupportStatus.EM_ANDAMENTO);
            }
        } else {
            ticket.setStatus(SupportStatus.AGUARDANDO_RESPOSTA);
            if (ticket.getAssignedToId() == null) ticket.setAssignedToId(user.id());
        }

        // Quem NÃO escreveu é avisado.
        if (ehCliente && ticket.getAssignedToId() != null) {
            notificationService.notifyUser(ticket.getAssignedToId(), NotificationType.SUPORTE_MENSAGEM,
                    "Nova mensagem no pedido de suporte", user.name() + " respondeu no pedido " + ticket.getReference() + ".",
                    NotificationChannel.IN_APP, "SupportTicket", ticketId, null);
        } else if (!ehCliente) {
            notificationService.notifyUser(ticket.getUserId(), NotificationType.SUPORTE_MENSAGEM,
                    "Nova resposta do Suporte", "O Suporte respondeu ao seu pedido " + ticket.getReference() + ".",
                    NotificationChannel.IN_APP, "SupportTicket", ticketId, null);
        }

        realtimeService.emitToTicket(ticketId, "support:message", SupportMessageDto.de(mensagem));
        return mensagem;
    }

    @Transactional
    public void marcarLidas(String ticketId, CurrentUser user) {
        ticketComAcesso(ticketId, user);
        messageRepository.marcarLidas(ticketId, user.id(), Instant.now());
    }

    /** Total de mensagens não lidas do utilizador em TODOS os seus pedidos — contador do cabeçalho. */
    @Transactional(readOnly = true)
    public long contarNaoLidas(CurrentUser user) {
        if (user.role() == PersonaRole.ADMIN_SISTEMA && podeGerirSuporte(user)) {
            return messageRepository.contarNaoLidasDoAssessor(user.id());
        }
        return messageRepository.contarNaoLidasDoCliente(user.id());
    }

    @Transactional
    public SupportTicket mudarEstado(String ticketId, CurrentUser admin, SupportStatus novoEstado) {
        if (!podeGerirSuporte(admin)) throw new ForbiddenException("Esta ação está reservada a quem gere Suporte.");
        SupportTicket ticket = ticketRepository.findById(ticketId).orElseThrow(() -> new NotFoundException("Pedido de suporte"));
        ticket.setStatus(novoEstado);
        realtimeService.emitToTicket(ticketId, "support:updated", SupportTicketDto.semExtras(ticket));
        return ticket;
    }
}
