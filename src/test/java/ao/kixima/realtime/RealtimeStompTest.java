package ao.kixima.realtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Teste de paridade de contrato para realtimeService.js, sobre STOMP: a
 * mesma sessão que autentica a API autentica o socket (sem sessão, nada
 * abre); a sala user:&lt;id&gt; só chega ao próprio; entrar na sala de um
 * pedido de suporte passa pela mesma regra de acesso do REST — o id vem do
 * cliente, a autorização nunca (recusa = frame ERROR, o {@code ack({ok:false})}
 * do Socket.IO).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RealtimeStompTest {

    private static final String PASSWORD = "Kixima@123";
    private static final String COMPRADOR_EMAIL = "comprador@petroangola.co.ao";
    private static final String ADMIN_SISTEMA_EMAIL = "admin@kixima.co.ao";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RealtimeService realtimeService;

    private final List<StompSession> sessoes = new ArrayList<>();

    /** Uma sessão STOMP ligada e a fila dos frames ERROR que o servidor lhe mandou. */
    private record Ligacao(StompSession session, BlockingQueue<String> erros) {
    }

    @AfterEach
    void fechar() {
        for (StompSession s : sessoes) if (s.isConnected()) s.disconnect();
    }

    private String login(String email) {
        Map<?, ?> corpo = rest.postForObject("/api/auth/login", Map.of("email", email, "password", PASSWORD), Map.class);
        assertThat(corpo).isNotNull();
        assertThat(corpo.get("token")).isNotNull();
        return (String) corpo.get("token");
    }

    private Ligacao ligar(String token) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());

        BlockingQueue<String> erros = new LinkedBlockingQueue<>();
        StompHeaders connect = new StompHeaders();
        if (token != null) connect.add("Authorization", "Bearer " + token);
        StompSession s = client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connect,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        // Só os frames ERROR chegam aqui (os MESSAGE vão para o handler da subscrição).
                        erros.offer(String.valueOf(headers.getFirst("message")));
                    }
                }).get(5, TimeUnit.SECONDS);
        sessoes.add(s);
        return new Ligacao(s, erros);
    }

    private BlockingQueue<Map<?, ?>> subscrever(StompSession s, String destino) throws Exception {
        BlockingQueue<Map<?, ?>> recebidas = new LinkedBlockingQueue<>();
        s.subscribe(destino, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                recebidas.offer((Map<?, ?>) payload);
            }
        });
        Thread.sleep(300); // o SUBSCRIBE é processado de forma assíncrona no servidor; sem RECEIPT no broker simples
        return recebidas;
    }

    @Test
    void semSessaoOConnectERecusado() {
        assertThatThrownBy(() -> ligar(null)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> ligar("nao.e.um.jwt")).isInstanceOf(Exception.class);
    }

    @Test
    void aSalaDoUtilizadorSoChegaAoProprio() throws Exception {
        String compradorId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", String.class, COMPRADOR_EMAIL);
        String adminId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", String.class, ADMIN_SISTEMA_EMAIL);

        Ligacao comprador = ligar(login(COMPRADOR_EMAIL));
        BlockingQueue<Map<?, ?>> recebidas = subscrever(comprador.session(), "/user/queue/notifications");

        realtimeService.emitToUser(adminId, "notification:new", Map.of("id", "do-admin"));
        realtimeService.emitToUser(compradorId, "notification:new", Map.of("id", "do-comprador"));

        Map<?, ?> envelope = recebidas.poll(3, TimeUnit.SECONDS);
        assertThat(envelope).isNotNull();
        assertThat(envelope.get("event")).isEqualTo("notification:new");
        assertThat(((Map<?, ?>) envelope.get("payload")).get("id")).isEqualTo("do-comprador");
        // A do admin nunca chega a esta sessão; e nenhum ERROR foi enviado.
        assertThat(recebidas.poll(800, TimeUnit.MILLISECONDS)).isNull();
        assertThat(comprador.erros()).isEmpty();
    }

    @Test
    void aSalaDeSuporteSoParaQuemTemAcessoAoPedido() throws Exception {
        String meuTicket = jdbcTemplate.queryForObject("SELECT id FROM support_tickets WHERE reference = ?", String.class, "SUP-2026-00001");
        String ticketDeOutro = jdbcTemplate.queryForObject("SELECT id FROM support_tickets WHERE reference = ?", String.class, "SUP-2026-00002");
        String token = login(COMPRADOR_EMAIL);

        Ligacao comprador = ligar(token);
        BlockingQueue<Map<?, ?>> recebidas = subscrever(comprador.session(), "/topic/support/" + meuTicket);
        realtimeService.emitToTicket(meuTicket, "support:message", Map.of("body", "olá"));
        Map<?, ?> envelope = recebidas.poll(3, TimeUnit.SECONDS);
        assertThat(envelope).isNotNull();
        assertThat(envelope.get("event")).isEqualTo("support:message");
        assertThat(((Map<?, ?>) envelope.get("payload")).get("body")).isEqualTo("olá");
        assertThat(comprador.erros()).isEmpty();

        // O pedido de outra empresa: o id vem do cliente, a autorização nunca — ERROR, e nada chega.
        Ligacao intruso = ligar(token);
        BlockingQueue<Map<?, ?>> nada = subscrever(intruso.session(), "/topic/support/" + ticketDeOutro);
        String erro = intruso.erros().poll(3, TimeUnit.SECONDS);
        assertThat(erro).contains("Sem acesso");
        realtimeService.emitToTicket(ticketDeOutro, "support:message", Map.of("body", "segredo"));
        assertThat(nada.poll(800, TimeUnit.MILLISECONDS)).isNull();
    }
}
