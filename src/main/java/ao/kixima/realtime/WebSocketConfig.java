package ao.kixima.realtime;

import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import ao.kixima.security.CorsOrigins;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Spring WebSocket/STOMP no lugar do Socket.IO (decisão do plano, M6). O
 * cliente do frontend só muda no cutover deste domínio — até lá o Node
 * continua a servir o Socket.IO e isto fica pronto, lado a lado.
 *
 * Mapa das salas (realtimeService.js → destinos STOMP), para o cutover:
 * <pre>
 *   user:&lt;id&gt;            → /user/queue/notifications      (evento notification:new)
 *   support:&lt;ticketId&gt;   → /topic/support/{ticketId}      (support:message, support:updated)
 *   conversation:&lt;id&gt;    → /topic/conversation/{id}       (conversation:message, conversation:risk-alert)
 * </pre>
 * Cada mensagem é um envelope {@code { event, payload }} com o MESMO nome de
 * evento e o MESMO payload que o Socket.IO emite — o cliente troca
 * {@code socket.on(evento)} por uma subscrição por sala e despacha por
 * {@code event}. "support:join"/"conversation:join" passam a ser a própria
 * subscrição, autorizada em {@link RealtimeAuthInterceptor}.
 *
 * Endpoint {@code /ws} (WebSocket nativo) e {@code /ws/sockjs} (fallback,
 * o "polling" do Socket.IO). CORS: as MESMAS origens que o REST, vindas de
 * {@link CorsOrigins} — a cópia própria desta lista foi exactamente o bug
 * que o Node teve (login a funcionar no Android, chat mudo).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final RealtimeAuthInterceptor realtimeAuthInterceptor;
    private final RealtimeHandshakeInterceptor realtimeHandshakeInterceptor;
    private final CorsOrigins origens;

    /**
     * Agendador PRIVADO dos heartbeats do broker. De propósito não é um {@code @Bean}: um TaskScheduler no
     * contexto faria o Spring Boot desistir do seu agendador por omissão, e os jobs {@code @Scheduled}
     * passariam a correr nesta thread única.
     */
    private final ThreadPoolTaskScheduler heartbeatScheduler = criarHeartbeatScheduler();

    private static ThreadPoolTaskScheduler criarHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("stomp-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

    @PreDestroy
    void encerrarHeartbeat() {
        heartbeatScheduler.shutdown();
    }

    public WebSocketConfig(RealtimeAuthInterceptor realtimeAuthInterceptor,
                            RealtimeHandshakeInterceptor realtimeHandshakeInterceptor,
                            CorsOrigins origens) {
        this.realtimeAuthInterceptor = realtimeAuthInterceptor;
        this.realtimeHandshakeInterceptor = realtimeHandshakeInterceptor;
        this.origens = origens;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setErrorHandler(new RealtimeErrorHandler());
        comOrigens(registry.addEndpoint("/ws").addInterceptors(realtimeHandshakeInterceptor));
        comOrigens(registry.addEndpoint("/ws/sockjs").addInterceptors(realtimeHandshakeInterceptor)).withSockJS();
    }

    /**
     * `cors: { origin: corsConfig.origin }` do Socket.IO. O STOMP só aceita
     * listas estáticas, por isso: qualquer origem em desenvolvimento/teste
     * (como `origin()` devolve nesses ambientes), a allow-list nos restantes.
     */
    private StompWebSocketEndpointRegistration comOrigens(StompWebSocketEndpointRegistration endpoint) {
        if (origens.aceitaQualquerOrigem()) return endpoint.setAllowedOriginPatterns("*");
        return endpoint.setAllowedOrigins(origens.allowList().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Heartbeats a cada 10 s nos dois sentidos: sem eles uma ligação parada (ninguém a escrever no chat) é
        // fechada em silêncio pelo proxy do Render por inatividade, e o frontend só dava por isso ao falhar.
        // O Socket.IO do Node fazia o equivalente com os seus pings.
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{10_000, 10_000})
                .setTaskScheduler(heartbeatScheduler);
        registry.setUserDestinationPrefix("/user");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(realtimeAuthInterceptor);
    }
}
