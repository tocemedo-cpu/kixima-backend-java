package ao.kixima.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Ligação ao RabbitMQ a partir de {@code RABBITMQ_URL} (a mesma variável que
 * o Node), só quando definida — sem ela, fica a auto-configuração do Spring
 * (nunca usada: {@link EventBus} é no-op sem URL). O exchange é o mesmo
 * {@code assertExchange(EXCHANGE, 'topic', { durable: true })} do Node,
 * declarado pelo RabbitAdmin na primeira ligação.
 */
@Configuration
public class RabbitConfig {

    @Bean
    public TopicExchange kiximaEventsExchange(@Value("${kixima.events.exchange:kixima.events}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    @ConditionalOnExpression("!'${kixima.events.url:}'.isBlank()")
    public ConnectionFactory rabbitConnectionFactory(@Value("${kixima.events.url}") String url) {
        CachingConnectionFactory cf = new CachingConnectionFactory();
        cf.setUri(url.trim());
        cf.setConnectionTimeout(5_000); // não-bloqueante: um broker ausente nunca prende uma operação de negócio
        return cf;
    }
}
