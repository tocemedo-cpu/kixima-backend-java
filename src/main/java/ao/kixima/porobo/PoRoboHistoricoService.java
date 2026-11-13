package ao.kixima.porobo;

import ao.kixima.audit.AuditLog;
import ao.kixima.audit.AuditLogRepository;
import ao.kixima.porobo.dto.PoRoboActividadeDto;
import ao.kixima.porobo.dto.PoRoboExecucaoDto;
import ao.kixima.porobo.dto.PoRoboPrevisaoDto;
import ao.kixima.porobo.dto.PoRoboRegraDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Leituras do PO Robot de UMA empresa: previsões (a última de cada regra), execuções e actividade. Só lê; nunca
 * escreve. Tudo filtrado por {@code companyId}.
 */
@Service
public class PoRoboHistoricoService {

    static final int LIMITE_EXECUCOES = 100;
    static final int LIMITE_ACTIVIDADES = 200;

    private final PoRoboPrevisaoRepository previsaoRepository;
    private final PoRoboExecucaoRepository execucaoRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public PoRoboHistoricoService(PoRoboPrevisaoRepository previsaoRepository, PoRoboExecucaoRepository execucaoRepository,
                                  AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.previsaoRepository = previsaoRepository;
        this.execucaoRepository = execucaoRepository;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<PoRoboPrevisaoDto> previsoes(String companyId) {
        return previsaoRepository.ultimasPorRegra(companyId).stream()
                .map(p -> PoRoboPrevisaoDto.de(p, PoRoboJson.lerPassos(p.getJustificacao()))).toList();
    }

    @Transactional(readOnly = true)
    public List<PoRoboExecucaoDto> execucoes(String companyId) {
        return execucaoRepository.findByCompanyIdOrderByIniciadaEmDesc(companyId, PageRequest.of(0, LIMITE_EXECUCOES)).stream()
                .map(e -> PoRoboExecucaoDto.de(e, PoRoboJson.lerTextos(e.getErros()))).toList();
    }

    @Transactional(readOnly = true)
    public List<PoRoboActividadeDto> actividades(String companyId) {
        return auditLogRepository.findByCompanyIdAndActionInOrderByCreatedAtDesc(companyId, PoRoboAuditoria.ACCOES_DE_ACTIVIDADE,
                PageRequest.of(0, LIMITE_ACTIVIDADES)).stream().map(this::paraActividade).toList();
    }

    private PoRoboActividadeDto paraActividade(AuditLog a) {
        JsonNode detail = lerDetalhe(a.getDetail());
        String tipo = tipoDe(a.getAction(), detail);
        // actorId nulo = o próprio robot (autor: null no contrato).
        PoRoboRegraDto.Ref autor = a.getActorId() == null ? null : new PoRoboRegraDto.Ref(a.getActorId(), a.getActorName());
        String referencia = a.getEntityRef() != null ? a.getEntityRef() : a.getEntityId();
        return new PoRoboActividadeDto(a.getId(), a.getCreatedAt(), tipo, descricao(tipo, detail), autor, referencia);
    }

    private JsonNode lerDetalhe(String json) {
        if (json == null || json.isBlank()) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    static String tipoDe(String accao, JsonNode detail) {
        return switch (accao) {
            case PoRoboAuditoria.REGRA_CRIADA -> "REGRA_CRIADA";
            case PoRoboAuditoria.REGRA_ATUALIZADA -> {
                // Só activar/desactivar: o corpo tinha apenas `ativo` (sem os campos da regra).
                if (detail.has("ativo") && !detail.has("mediaMensal")) yield detail.path("ativo").asBoolean() ? "REGRA_ACTIVADA" : "REGRA_DESACTIVADA";
                yield "REGRA_ALTERADA";
            }
            case PoRoboAuditoria.REGRA_REMOVIDA -> "REGRA_REMOVIDA";
            case PoRoboAuditoria.EXECUCAO -> "EXECUCAO";
            case PoRoboAuditoria.PROPOSTA_CRIADA -> "PROPOSTA_CRIADA";
            case PoRoboAuditoria.PROPOSTA_AJUSTADA -> "PROPOSTA_AJUSTADA";
            case PoRoboAuditoria.PROPOSTA_SUBMETIDA -> "PROPOSTA_SUBMETIDA";
            case PoRoboAuditoria.PROPOSTA_REJEITADA -> "PROPOSTA_REJEITADA";
            default -> "PROPOSTA_EXPIRADA";
        };
    }

    static String descricao(String tipo, JsonNode d) {
        String produto = d.hasNonNull("produto") ? "«" + d.get("produto").asText() + "»" : "um produto";
        return switch (tipo) {
            case "REGRA_CRIADA" -> "Regra criada para " + produto + ".";
            case "REGRA_ALTERADA" -> "Regra de " + produto + " alterada.";
            case "REGRA_ACTIVADA" -> "Regra de " + produto + " activada.";
            case "REGRA_DESACTIVADA" -> "Regra de " + produto + " desactivada.";
            case "REGRA_REMOVIDA" -> "Regra de " + produto + " removida.";
            case "EXECUCAO" -> "Execução do robot: " + d.path("regrasAvaliadas").asInt() + " regra(s) avaliada(s), "
                    + d.path("propostasCriadas").asInt() + " proposta(s) criada(s)"
                    + (d.path("erros").asInt() > 0 ? ", " + d.path("erros").asInt() + " erro(s)." : ".");
            case "PROPOSTA_CRIADA" -> "Proposta criada: " + d.path("quantidade").asInt() + " un de " + produto
                    + (d.path("limiteAplicado").asBoolean() ? " (reduzida pelo limite de segurança; calculadas " + d.path("quantidadeOriginal").asInt() + ")." : ".");
            case "PROPOSTA_AJUSTADA" -> "Quantidade de " + produto + " ajustada de " + d.path("de").asInt() + " para " + d.path("para").asInt() + " un.";
            case "PROPOSTA_SUBMETIDA" -> "Proposta de " + produto + " submetida"
                    + (d.hasNonNull("purchaseOrderReference") ? ": PO " + d.get("purchaseOrderReference").asText() + " aguarda aprovação." : ".");
            case "PROPOSTA_REJEITADA" -> "Proposta de " + produto + " rejeitada" + (d.hasNonNull("motivo") ? ": " + d.get("motivo").asText() : ".");
            default -> "Proposta de " + produto + " expirou" + (d.hasNonNull("motivo") ? ": " + d.get("motivo").asText() : ".");
        };
    }
}
