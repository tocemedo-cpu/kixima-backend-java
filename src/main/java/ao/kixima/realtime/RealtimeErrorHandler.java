package ao.kixima.realtime;

import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * O frame ERROR que o cliente recebe quando um CONNECT/SUBSCRIBE é recusado
 * leva a razão real ("Sessão em falta.", "Sem acesso.", ...) no cabeçalho
 * {@code message} — o {@code ack({ ok:false, error })} do Socket.IO — em vez
 * do texto genérico do canal ("Failed to send message to ...") que embrulha
 * a excepção do interceptor.
 */
public class RealtimeErrorHandler extends StompSubProtocolErrorHandler {

    @Override
    @Nullable
    public Message<byte[]> handleClientMessageProcessingError(@Nullable Message<byte[]> clientMessage, Throwable ex) {
        Throwable causa = ex;
        while (causa != null && !(causa instanceof RealtimeAuthInterceptor.RealtimeAccessDeniedException)) {
            causa = causa.getCause();
        }
        return super.handleClientMessageProcessingError(clientMessage, causa != null ? causa : ex);
    }
}
