package ao.kixima.realtime;

import ao.kixima.security.SessionCookieUtil;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Espelha a primeira metade de {@code tokenDoHandshake} (realtimeService.js):
 * o MESMO cookie httpOnly de sessão que a API usa vem no pedido HTTP de
 * upgrade — guarda-se aqui, porque no CONNECT (onde a autenticação acontece)
 * já não há pedido HTTP. O Bearer (Capacitor/testes) vem no próprio CONNECT.
 */
@Component
public class RealtimeHandshakeInterceptor implements HandshakeInterceptor {

    static final String ATRIBUTO_TOKEN = "kixima.tokenDoCookie";

    private final SessionCookieUtil sessionCookieUtil;

    public RealtimeHandshakeInterceptor(SessionCookieUtil sessionCookieUtil) {
        this.sessionCookieUtil = sessionCookieUtil;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servlet) {
            String token = sessionCookieUtil.ler(servlet.getServletRequest());
            if (token != null) attributes.put(ATRIBUTO_TOKEN, token);
        }
        return true; // a decisão é no CONNECT, tal como io.use(autenticarSocket) — nunca no upgrade HTTP.
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                               Exception exception) {
        // nada a limpar
    }
}
