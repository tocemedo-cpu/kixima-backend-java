package ao.kixima.realtime;

import ao.kixima.conversation.ConversationService;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.JwtService;
import ao.kixima.support.SupportChatService;
import ao.kixima.user.User;
import ao.kixima.user.UserRepository;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Espelha {@code autenticarSocket} + {@code registarSalaDinamica} de
 * realtimeService.js, sobre STOMP:
 * <ul>
 *   <li>CONNECT — a mesma sessão que autentica a API autentica o socket:
 *   o MESMO cookie (guardado no handshake) ou o MESMO Bearer, o MESMO JWT,
 *   e vai SEMPRE à base buscar o utilizador (só o id vem do token): inativo,
 *   {@code tokenVersion} revogada ou sessão expirada não abrem socket
 *   nenhum, exatamente como não chamam a API.</li>
 *   <li>SUBSCRIBE — entrar numa sala nunca é aceite às cegas: o id vem do
 *   cliente, a AUTORIZAÇÃO nunca — é revalidada aqui, contra a base, pela
 *   mesma regra que a rota REST equivalente aplica
 *   ({@link SupportChatService#ticketComAcesso},
 *   {@link ConversationService#conversationComAcesso}).</li>
 * </ul>
 * Os serviços de chat entram {@code @Lazy} pela mesma razão do
 * {@code setAutorizadores} do Node: eles dependem do RealtimeService para
 * emitir, não o inverso — sem isto havia um ciclo de dependências.
 */
@Component
public class RealtimeAuthInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RealtimeAuthInterceptor.class);

    /** {@code /user/queue/notifications} — a sala {@code user:<id>}; o Spring garante que só chega ao próprio. */
    static final String DESTINO_NOTIFICACOES = "/user/queue/notifications";
    static final Pattern SALA_SUPORTE = Pattern.compile("^/topic/support/([^/]+)$");
    static final Pattern SALA_CONVERSA = Pattern.compile("^/topic/conversation/([^/]+)$");

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final SupportChatService supportChatService;
    private final ConversationService conversationService;

    public RealtimeAuthInterceptor(JwtService jwtService, UserRepository userRepository,
                                    @Lazy SupportChatService supportChatService,
                                    @Lazy ConversationService conversationService) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.supportChatService = supportChatService;
        this.conversationService = conversationService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) return message;

        switch (accessor.getCommand()) {
            case CONNECT -> accessor.setUser(autenticar(accessor));
            case SUBSCRIBE -> autorizarSala(accessor);
            default -> {
                // SEND/UNSUBSCRIBE/DISCONNECT: nada a decidir — não há destinos /app neste marco.
            }
        }
        return message;
    }

    private String tokenDoConnect(StompHeaderAccessor accessor) {
        Map<String, Object> atributos = accessor.getSessionAttributes();
        Object doCookie = atributos == null ? null : atributos.get(RealtimeHandshakeInterceptor.ATRIBUTO_TOKEN);
        if (doCookie instanceof String s && !s.isBlank()) return s;
        // Bearer, para clientes programáticos/testes — o mesmo fallback que a API aceita (auth.token no Node).
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null) return null;
        String[] parts = header.split(" ", 2);
        return parts.length == 2 && "Bearer".equals(parts[0]) && !parts[1].isBlank() ? parts[1] : null;
    }

    private RealtimePrincipal autenticar(StompHeaderAccessor accessor) {
        String token = tokenDoConnect(accessor);
        if (token == null) throw new RealtimeAccessDeniedException("Sessão em falta.");

        Claims claims;
        try {
            claims = jwtService.verify(token);
        } catch (Exception e) {
            throw new RealtimeAccessDeniedException("Token inválido ou expirado.");
        }

        User user = userRepository.findByIdWithCompany(claims.getSubject()).orElse(null);
        if (user == null || !user.isActive()) throw new RealtimeAccessDeniedException("Utilizador inválido ou inativo.");

        Integer tvClaim = claims.get("tv", Integer.class);
        if ((tvClaim == null ? 0 : tvClaim) != user.getTokenVersion()) throw new RealtimeAccessDeniedException("Sessão terminada.");

        return new RealtimePrincipal(new CurrentUser(user.getId(), user.getRole(), user.getAdminAreas(), user.getCompanyId(),
                user.getCompany() == null ? null : user.getCompany().getType(),
                user.getCompany() == null ? null : user.getCompany().getPlan(),
                user.getApprovalCap(), user.getName(), user.getEmail(), user.getAvatarUrl(), false, false, null));
    }

    private void autorizarSala(StompHeaderAccessor accessor) {
        String destino = accessor.getDestination();
        if (!(accessor.getUser() instanceof RealtimePrincipal principal)) {
            throw new RealtimeAccessDeniedException("Sessão em falta.");
        }
        if (destino == null) throw new RealtimeAccessDeniedException("Sem acesso.");
        if (DESTINO_NOTIFICACOES.equals(destino)) return; // o próprio, sempre — o Spring já o prende ao principal.

        Matcher suporte = SALA_SUPORTE.matcher(destino);
        if (suporte.matches()) {
            if (!podeEntrar(() -> supportChatService.ticketComAcesso(suporte.group(1), principal.user()), "support", suporte.group(1))) {
                throw new RealtimeAccessDeniedException("Sem acesso.");
            }
            return;
        }
        Matcher conversa = SALA_CONVERSA.matcher(destino);
        if (conversa.matches()) {
            if (!podeEntrar(() -> conversationService.conversationComAcesso(conversa.group(1), principal.user()), "conversation", conversa.group(1))) {
                throw new RealtimeAccessDeniedException("Sem acesso.");
            }
            return;
        }
        throw new RealtimeAccessDeniedException("Sem acesso.");
    }

    /** Mesma regra de acesso do REST, sem lançar — o handshake só quer sim/não (podeEntrarNoTicket/podeEntrarNaConversa). */
    private boolean podeEntrar(Runnable verificacao, String prefixo, String id) {
        try {
            verificacao.run();
            return true;
        } catch (ao.kixima.common.error.AppException negado) {
            return false;
        } catch (Exception err) {
            log.error("Falha ao entrar na sala {} ({}): {}", prefixo, id, err.getMessage());
            return false;
        }
    }

    /** Vira um frame STOMP ERROR para o cliente (o {@code ack({ ok:false })} do Socket.IO). */
    public static class RealtimeAccessDeniedException extends RuntimeException {
        public RealtimeAccessDeniedException(String message) {
            super(message);
        }
    }
}
