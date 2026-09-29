package ao.kixima.messaging;

import ao.kixima.invoice.Invoice;
import ao.kixima.invoice.InvoiceRepository;
import ao.kixima.po.PurchaseOrder;
import ao.kixima.po.PurchaseOrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de paridade de contrato para eventBus.js: totalmente opcional e
 * não-bloqueante (sem RABBITMQ_URL é no-op; com broker indisponível nunca
 * lança), e os payloads canónicos que a integração ERP consome.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EventBusTest {

    @Autowired
    private EventBus eventBus;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void semRabbitmqUrlNadaEPublicadoEOFluxoSegue() {
        assertThat(eventBus.ativo()).isFalse();
        eventBus.publish("purchase_order.approved", Map.of("poId", "x"), "po-approved:x", null); // não lança
        assertThat(eventBus.publicarAgora("purchase_order.approved", Map.of("poId", "x"), "po-approved:x", null)).isFalse();
    }

    @Test
    void comBrokerIndisponivelAPublicacaoFalhaSemLancarNemPrender() {
        CachingConnectionFactory morto = new CachingConnectionFactory("localhost", 1);
        morto.setConnectionTimeout(1_000);
        RabbitTemplate template = new RabbitTemplate(morto);
        EventBus comUrl = new EventBus(new ProviderFixo<>(template), new ProviderFixo<>(morto), objectMapper,
                "amqp://localhost:1", "kixima.events");

        long inicio = System.currentTimeMillis();
        assertThat(comUrl.ativo()).isTrue();
        assertThat(comUrl.publicarAgora("goods.received", Map.of("poReference", "PO-X"), "goods-received:x", null)).isFalse();
        assertThat(System.currentTimeMillis() - inicio).isLessThan(10_000);
        morto.destroy();
    }

    @Test
    void payloadsCanonicosDaIntegracao() {
        String poId = jdbcTemplate.queryForObject("SELECT id FROM purchase_orders WHERE reference = ?", String.class, "PO-2026-00003");
        PurchaseOrder po = purchaseOrderRepository.findById(poId).orElseThrow();
        Invoice invoice = invoiceRepository.findById(
                jdbcTemplate.queryForObject("SELECT id FROM invoices WHERE reference = ?", String.class, "FAT-2026-00002")).orElseThrow();

        Instant agora = Instant.now();
        Map<String, Object> aprovada = EventPayloads.purchaseOrderApproved(po, agora);
        assertThat(aprovada).containsKeys("poId", "reference", "buyer", "supplier", "currency", "totalAmount", "lines", "approvedAt");
        assertThat(aprovada.get("reference")).isEqualTo("PO-2026-00003");
        assertThat(((Map<?, ?>) aprovada.get("buyer")).get("taxId")).isEqualTo("AO-CLI-0001");
        assertThat(((Map<?, ?>) aprovada.get("supplier")).get("taxId")).isEqualTo("AO-FOR-0001");
        // Nos eventos os valores são números (Number(d) no eventBus.js), não o texto Decimal das respostas HTTP.
        assertThat(((Number) aprovada.get("totalAmount")).doubleValue()).isPositive();
        List<?> lines = (List<?>) aprovada.get("lines");
        assertThat(lines).hasSize(1);
        Map<String, Object> linha = mapa(lines.get(0));
        assertThat(linha).containsKeys("sku", "description", "quantity", "unitPrice", "lineTotal");
        assertThat(((Number) linha.get("quantity")).intValue()).isPositive();
        assertThat(aprovada.get("approvedAt")).isEqualTo(agora.toString());

        Map<String, Object> fatura = EventPayloads.invoiceIssued(invoice, po);
        assertThat(fatura.get("reference")).isEqualTo("FAT-2026-00002");
        assertThat(fatura.get("poReference")).isEqualTo("PO-2026-00003");
        assertThat(((Map<?, ?>) fatura.get("supplier")).get("taxId")).isEqualTo("AO-FOR-0001");
        assertThat(fatura).containsKeys("invoiceId", "currency", "amount", "issuedAt", "dueAt");

        Map<String, Object> rececao = EventPayloads.goodsReceived(po, agora);
        assertThat(rececao.get("goodsReceiptId")).isEqualTo("gr:" + poId);
        assertThat(mapa(((List<?>) rececao.get("lines")).get(0))).containsKeys("sku", "description", "quantityReceived");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapa(Object o) {
        return (Map<String, Object>) o;
    }

    /** Um ObjectProvider que devolve sempre a mesma instância — para construir o EventBus à mão no teste. */
    private static final class ProviderFixo<T> implements org.springframework.beans.factory.ObjectProvider<T> {
        private final T valor;

        ProviderFixo(T valor) {
            this.valor = valor;
        }

        @Override
        public T getObject(Object... args) {
            return valor;
        }

        @Override
        public T getIfAvailable() {
            return valor;
        }

        @Override
        public T getIfUnique() {
            return valor;
        }

        @Override
        public T getObject() {
            return valor;
        }
    }
}
