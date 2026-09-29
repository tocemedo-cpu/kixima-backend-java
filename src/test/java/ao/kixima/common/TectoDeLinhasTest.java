package ao.kixima.common;

import ao.kixima.audit.AuditLog;
import ao.kixima.audit.AuditLogRepository;
import ao.kixima.common.persistence.TectoDeLinhasAspect;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O tecto de DB_MAX_ROWS aplica-se a QUALQUER leitura em lista por
 * repositório, como a extensão do Prisma em config/database.js: corta em N,
 * grita quando corta, e deixa em paz o que já vem paginado e o que não passa
 * pelo Hibernate. Tecto baixado para 5 só neste contexto, para o teste não
 * ter de inserir mil linhas na base partilhada.
 */
@SpringBootTest(properties = "kixima.db.max-rows=5")
@ActiveProfiles("test")
@Transactional
class TectoDeLinhasTest {

    private static final int TECTO = 5;

    @Autowired
    private AuditLogRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ListAppender<ILoggingEvent> registos = new ListAppender<>();
    private String acao;

    @BeforeEach
    void prepara() {
        Logger logger = (Logger) LoggerFactory.getLogger(TectoDeLinhasAspect.class);
        registos.start();
        logger.addAppender(registos);

        acao = "TESTE_TECTO_" + UUID.randomUUID();
        for (int i = 0; i < TECTO + 1; i++) {
            repository.save(new AuditLog(UUID.randomUUID().toString(), acao, "Teste", null, "ref-" + i,
                    null, null, null, null, null, null, Instant.now().minusSeconds(i)));
        }
        repository.flush();
    }

    @AfterEach
    void limpa() {
        ((Logger) LoggerFactory.getLogger(TectoDeLinhasAspect.class)).detachAppender(registos);
    }

    private Specification<AuditLog> daAcao() {
        return (root, q, cb) -> cb.equal(root.get("action"), acao);
    }

    private List<ILoggingEvent> erros() {
        return registos.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
    }

    @Test
    void umaLeituraSemPaginacaoECortadaNoTectoEGrita() {
        List<AuditLog> lista = repository.findAll(daAcao(), Sort.by("createdAt"));

        assertThat(lista).hasSize(TECTO);
        assertThat(erros()).hasSize(1);
        String mensagem = erros().get(0).getFormattedMessage();
        assertThat(mensagem).startsWith("Leitura de auditLog atingiu o tecto de 5 linhas e foi truncada. "
                + "Qualquer total calculado a partir daqui está ERRADO. "
                + "Acrescente paginação a este endpoint ou um take explícito.");
        assertThat(mensagem).contains("modelo=auditLog").contains("onde=ao.kixima.common.TectoDeLinhasTest");
    }

    @Test
    void umaLeituraPaginadaFicaIntacta() {
        // Paginada: o chamador assumiu o limite — nem corte pelo tecto, nem aviso.
        assertThat(repository.findAll(daAcao(), PageRequest.of(0, 10)).getContent()).hasSize(TECTO + 1);
        assertThat(repository.count(daAcao())).isEqualTo(TECTO + 1);

        // Lista com Pageable explícito exactamente do tamanho do tecto: `take` explícito no Node — não avisa.
        assertThat(repository.findByActionAndEntityRefOrderByCreatedAtDesc(acao, "ref-0", PageRequest.of(0, TECTO))).hasSize(1);
        assertThat(erros()).isEmpty();
    }

    @Test
    void oJdbcTemplateNaoPassaPeloTecto() {
        // O `$queryRaw` do Node também não passa pela extensão do Prisma.
        assertThat(jdbcTemplate.queryForList("SELECT id FROM audit_logs WHERE action = ?", String.class, acao)).hasSize(TECTO + 1);
        assertThat(erros()).isEmpty();
    }
}
