package ao.kixima.audit;

import ao.kixima.audit.dto.AuditActionCount;
import ao.kixima.audit.dto.AuditLogDto;
import ao.kixima.audit.dto.AuditLogListResponse;
import ao.kixima.common.error.SentryReporter;
import ao.kixima.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Espelha backend/src/services/auditService.js — trilho de auditoria
 * APPEND-ONLY: {@code actorFrom}/{@code anonimoFrom}/
 * {@code contextoFrom}/{@code recordSafe} (escrita, M1) e {@code list()}
 * (leitura paginada/filtrável para o Admin do Sistema, M5).
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int POR_OMISSAO = 25;
    private static final int MAXIMO = 100;

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final SentryReporter sentry;

    public AuditService(AuditLogRepository repository, ObjectMapper objectMapper, JdbcTemplate jdbcTemplate,
                        SentryReporter sentry) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.sentry = sentry;
    }

    public Actor actorFrom(CurrentUser user, HttpServletRequest req) {
        if (user == null) return anonimoFrom(req);
        return new Actor(user.id(), user.name(), user.role() == null ? null : user.role().name(),
                user.companyId(), req.getRemoteAddr());
    }

    public Actor anonimoFrom(HttpServletRequest req) {
        return Actor.anonimo(req.getRemoteAddr());
    }

    public Map<String, Object> contextoFrom(HttpServletRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ip", req.getRemoteAddr());
        String agente = req.getHeader("user-agent");
        m.put("agente", agente == null ? null : agente.substring(0, Math.min(180, agente.length())));
        return m;
    }

    public record Entry(Actor actor, String action, String entityType, String entityId,
                         String entityRef, Object detail) {
        public Entry(Actor actor, String action, String entityType) {
            this(actor, action, entityType, null, null, null);
        }
    }

    /**
     * Espelha {@code auditService.record(tx, ...)} — DENTRO da transação de
     * negócio, e a falhar com ela: um pagamento sem registo não existe.
     */
    public void record(Entry entry) {
        try {
            String detailJson = entry.detail() == null ? null : objectMapper.writeValueAsString(entry.detail());
            Actor a = entry.actor() == null ? Actor.anonimo(null) : entry.actor();
            repository.save(new AuditLog(UUID.randomUUID().toString(), entry.action(), entry.entityType(),
                    entry.entityId(), entry.entityRef(), a.actorId(), a.actorName(), a.actorRole(),
                    a.companyId(), a.ip(), detailJson, Instant.now()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Falha a serializar o detalhe do registo de auditoria.", e);
        }
    }

    /**
     * Variante "não pode partir o negócio": regista após o sucesso da
     * operação; uma falha aqui é grave (fica um buraco no trilho) — vai para
     * o log e Sentry.
     */
    public void recordSafe(Entry entry) {
        try {
            String detailJson = entry.detail() == null ? null : objectMapper.writeValueAsString(entry.detail());
            Actor a = entry.actor() == null ? Actor.anonimo(null) : entry.actor();
            AuditLog row = new AuditLog(UUID.randomUUID().toString(), entry.action(), entry.entityType(),
                    entry.entityId(), entry.entityRef(), a.actorId(), a.actorName(), a.actorRole(),
                    a.companyId(), a.ip(), detailJson, Instant.now());
            repository.save(row);
        } catch (Exception e) {
            log.error("AUDITORIA FALHOU — registo perdido: action={}", entry.action(), e);
            sentry.captureException(e);
        }
    }

    /** Listagem paginada/filtrável para o Admin do Sistema — GET /api/admin/audit-logs. */
    @Transactional(readOnly = true)
    public AuditLogListResponse list(Integer page, Integer limit, String action, String q) {
        int take = Math.min(Math.max(1, limit == null ? POR_OMISSAO : limit), MAXIMO);
        int current = Math.max(1, page == null ? 1 : page);

        Page<AuditLog> pagina = repository.findAll(AuditLogSpecifications.comFiltros(action, q),
                PageRequest.of(current - 1, take, Sort.by(Sort.Direction.DESC, "createdAt")));

        List<Map<String, Object>> contagens = jdbcTemplate.queryForList(
                "SELECT action, COUNT(*) AS total FROM audit_logs GROUP BY action ORDER BY total DESC");
        List<AuditActionCount> actions = contagens.stream()
                .map(r -> new AuditActionCount((String) r.get("action"), ((Number) r.get("total")).longValue()))
                .toList();

        return new AuditLogListResponse(pagina.getContent().stream().map(this::toDto).toList(),
                pagina.getTotalElements(), current, Math.max(1, pagina.getTotalPages()), actions);
    }

    public AuditLogDto toDto(AuditLog row) {
        JsonNode detail = null;
        if (row.getDetail() != null) {
            try {
                detail = objectMapper.readTree(row.getDetail());
            } catch (Exception ignorado) {
                // detail corrompido/não-JSON — fica null em vez de rebentar a listagem inteira.
            }
        }
        return new AuditLogDto(row.getId(), row.getAction(), row.getEntityType(), row.getEntityId(), row.getEntityRef(),
                row.getActorId(), row.getActorName(), row.getActorRole(), row.getCompanyId(), row.getIp(), detail, row.getCreatedAt());
    }
}
