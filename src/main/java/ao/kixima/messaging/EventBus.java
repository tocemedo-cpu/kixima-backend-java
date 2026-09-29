package ao.kixima.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Espelha backend/src/services/eventBus.js — publicação de eventos de negócio
 * no RabbitMQ (exchange {@code kixima.events}, topic) para o
 * kixima-integration-service consumir. Totalmente OPCIONAL e
 * não-bloqueante: sem {@code RABBITMQ_URL}, ou com o broker indisponível,
 * {@link #publish} é um no-op com aviso no log, e o fluxo do Kixima segue
 * exactamente igual. NUNCA lança.
 *
 * Regra do plano (M6): a publicação nunca corre dentro de uma fronteira
 * {@code @Transactional} de negócio — havendo transação activa, o envio é
 * adiado para {@code afterCommit} (o payload é construído ANTES, dentro da
 * transação, para as relações lazy ainda estarem à mão). É o equivalente
 * ao Node, que publica depois do {@code prisma.$transaction} fechar.
 *
 * NÃO PORTADO (sem chamador Java ainda): {@code purchase_order.approval_requested}
 * (POs ERP-managed — ver PoService, javadoc) e {@code payment.completed}
 * (domínio de pagamento) — entram com esses domínios.
 */
@Service
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    /** O envelope canónico consumido pela integração. */
    public record Envelope(String eventId, String tenantId, String source, String occurredAt, Object payload) {
    }

    private final ObjectProvider<RabbitTemplate> rabbitTemplate;
    private final ObjectProvider<ConnectionFactory> connectionFactory;
    private final ObjectMapper objectMapper;
    private final String url;
    private final String exchange;

    public EventBus(ObjectProvider<RabbitTemplate> rabbitTemplate, ObjectProvider<ConnectionFactory> connectionFactory,
                     ObjectMapper objectMapper,
                     @Value("${kixima.events.url:}") String url,
                     @Value("${kixima.events.exchange:kixima.events}") String exchange) {
        this.rabbitTemplate = rabbitTemplate;
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
        this.url = url == null ? "" : url.trim();
        this.exchange = exchange;
    }

    public boolean ativo() {
        return !url.isBlank();
    }

    public String exchange() {
        return exchange;
    }

    /** Espelha init(): regista nos logs, no arranque, se a integração ERP está ligada — sem executar nenhuma ação. */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (!ativo()) {
            log.warn("eventBus: RABBITMQ_URL não definido — integração ERP DESATIVADA (o Kixima funciona normalmente)");
            return;
        }
        ConnectionFactory cf = connectionFactory.getIfAvailable();
        if (cf == null) {
            log.warn("eventBus: sem ligação RabbitMQ configurada — integração ERP DESATIVADA");
            return;
        }
        try (var ligacao = cf.createConnection()) {
            log.info("eventBus: integração ERP ATIVA — ligado ao RabbitMQ (exchange {})", exchange);
        } catch (Exception err) {
            log.warn("eventBus: não foi possível ligar ao RabbitMQ no arranque (tentará novamente ao publicar): {}", err.getMessage());
        }
    }

    /**
     * Publica um evento no exchange. {@code eventId} estável para idempotência
     * (ex.: {@code po-approved:<id>}); {@code tenantId} é a operadora/cliente
     * dona da transação.
     */
    public void publish(String routingKey, Object payload, String eventId, String tenantId) {
        if (!ativo()) {
            log.warn("eventBus: RABBITMQ_URL não definido — evento NÃO publicado ({})", routingKey);
            return;
        }
        Envelope envelope = new Envelope(eventId == null ? UUID.randomUUID().toString() : eventId, tenantId, "kixima",
                Instant.now().toString(), payload);
        byte[] corpo;
        try {
            corpo = objectMapper.writeValueAsBytes(envelope);
        } catch (Exception err) {
            log.warn("eventBus: falha a serializar evento (ignorado) ({}): {}", routingKey, err.getMessage());
            return;
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enviar(routingKey, envelope.eventId(), corpo);
                }
            });
        } else {
            enviar(routingKey, envelope.eventId(), corpo);
        }
    }

    /** Devolve true se publicado; false se no-op/erro — nunca lança. */
    boolean enviar(String routingKey, String eventId, byte[] corpo) {
        RabbitTemplate template = rabbitTemplate.getIfAvailable();
        if (template == null) {
            log.warn("eventBus: sem canal (RabbitTemplate ausente) — evento NÃO publicado ({})", routingKey);
            return false;
        }
        try {
            MessageProperties props = new MessageProperties();
            props.setMessageId(eventId);
            props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            props.setContentEncoding(StandardCharsets.UTF_8.name());
            template.send(exchange, routingKey, new Message(corpo, props));
            log.info("eventBus: evento publicado ({}, eventId={})", routingKey, eventId);
            return true;
        } catch (Exception err) {
            log.warn("eventBus: falha ao publicar evento (ignorado) ({}): {}", routingKey, err.getMessage());
            return false;
        }
    }

    /** Atalho para os testes/chamadores que já têm o envelope pronto. */
    boolean publicarAgora(String routingKey, Map<String, Object> payload, String eventId, String tenantId) {
        if (!ativo()) return false;
        try {
            Envelope envelope = new Envelope(eventId, tenantId, "kixima", Instant.now().toString(), payload);
            return enviar(routingKey, eventId, objectMapper.writeValueAsBytes(envelope));
        } catch (Exception err) {
            return false;
        }
    }
}
