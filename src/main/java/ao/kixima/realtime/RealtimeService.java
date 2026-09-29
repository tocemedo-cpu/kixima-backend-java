package ao.kixima.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Espelha {@code emitToUser}/{@code emitToTicket}/{@code emitToConversation}
 * de realtimeService.js — emissões SEGURAS: nunca lançam (uma falha no push
 * fica no log; a notificação/mensagem já ficou gravada, que é a fonte da
 * verdade), e nenhum chamador precisa de verificar nada.
 *
 * DIVERGÊNCIA DELIBERADA face ao Node: lá, os serviços emitem depois do
 * {@code prisma.$transaction} fechar; aqui os serviços de negócio chamam
 * isto DENTRO da sua transação Spring — por isso, havendo transação activa,
 * o push é adiado para {@code afterCommit}: o cliente nunca recebe um evento
 * sobre uma linha que ainda não está (ou nunca chegue a estar) na base.
 */
@Service
public class RealtimeService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeService.class);

    /** O envelope de cada mensagem STOMP — o nome do evento Socket.IO e o mesmo payload. */
    public record Evento(String event, Object payload) {
    }

    private final SimpMessagingTemplate messagingTemplate;

    public RealtimeService(@Lazy SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void emitToUser(String userId, String event, Object payload) {
        if (userId == null) return;
        depoisDoCommit(() -> messagingTemplate.convertAndSendToUser(userId, "/queue/notifications", new Evento(event, payload)));
    }

    public void emitToTicket(String ticketId, String event, Object payload) {
        if (ticketId == null) return;
        depoisDoCommit(() -> messagingTemplate.convertAndSend("/topic/support/" + ticketId, new Evento(event, payload)));
    }

    public void emitToConversation(String conversationId, String event, Object payload) {
        if (conversationId == null) return;
        depoisDoCommit(() -> messagingTemplate.convertAndSend("/topic/conversation/" + conversationId, new Evento(event, payload)));
    }

    private void depoisDoCommit(Runnable envio) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enviarSeguro(envio);
                }
            });
        } else {
            enviarSeguro(envio);
        }
    }

    private void enviarSeguro(Runnable envio) {
        try {
            envio.run();
        } catch (Exception err) {
            log.warn("Push em tempo real falhou (a informação já ficou gravada): {}", err.getMessage());
        }
    }
}
