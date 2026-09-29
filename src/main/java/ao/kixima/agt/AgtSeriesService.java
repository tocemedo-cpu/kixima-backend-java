package ao.kixima.agt;

import ao.kixima.common.error.AgtRecusadoException;
import ao.kixima.common.error.BusinessRuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Espelha backend/src/services/agtSeriesService.js — pedido de série de
 * numeração à AGT ("Solicitar Série", DS.120 §4.5), pré-requisito para
 * emitir qualquer documento com série própria (o {@code documentNo} tem de
 * conter um {@code seriesCode} atribuído pela AGT, nunca inventado).
 *
 * {@code atribuirDocumentNo} está preparado para os 3 tipos (FT/NC/RC) via
 * SQL bruto contra as tabelas já existentes no schema partilhado
 * (invoices/credit_notes/payments) — mesmo quando CreditNote/Payment ainda
 * não têm entidade JPA própria neste marco (M4), a coluna
 * {@code agt_document_no} já existe nessas tabelas (migração
 * 20260922000000_agt_document_no) e este método só lê/escreve essa UMA
 * coluna, por isso não precisa da entidade completa. {@code TABELA_POR_TIPO}
 * é uma lista fechada, nunca um nome de tabela dinâmico por concatenação —
 * mesmo princípio de ReferenceCounterService.TABELA_DO_MODELO (M3).
 */
@Service
public class AgtSeriesService {

    private static final Logger log = LoggerFactory.getLogger(AgtSeriesService.class);

    private static final Map<String, String> TABELA_POR_TIPO = Map.of(
            "FT", "invoices", "NC", "credit_notes", "RC", "payments");

    private final AgtSigningService agtSigningService;
    private final AgtSandboxClient agtSandboxClient;
    private final AgtSeriesFeRepository repository;
    private final JdbcTemplate jdbcTemplate;

    public AgtSeriesService(AgtSigningService agtSigningService, AgtSandboxClient agtSandboxClient,
                             AgtSeriesFeRepository repository, JdbcTemplate jdbcTemplate) {
        this.agtSigningService = agtSigningService;
        this.agtSandboxClient = agtSandboxClient;
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ParametrosSerie(String taxRegistrationNumber, int seriesYear, String documentType,
                                   String establishmentNumber, String seriesContingencyIndicator) {
        public ParametrosSerie(String taxRegistrationNumber, int seriesYear, String documentType, String establishmentNumber) {
            this(taxRegistrationNumber, seriesYear, documentType, establishmentNumber, "N");
        }
    }

    /**
     * `documentType` ∈ FT/FR/NC/RC/ND/... `seriesContingencyIndicator`: "N"
     * (regime normal, único usado neste ambiente) ou "C" (contingência).
     */
    public Map<String, Object> construirPedidoSerie(ParametrosSerie p) {
        log.info("Solicitar Série: a construir pedido para {} {} ano={} estabelecimento={}",
                p.documentType(), p.taxRegistrationNumber(), p.seriesYear(), p.establishmentNumber());

        // dadosAssinatura: exactamente os campos que entram na assinatura JWS deste pedido.
        Map<String, Object> dadosAssinatura = AgtJson.mapa(
                "taxRegistrationNumber", p.taxRegistrationNumber(),
                "seriesYear", p.seriesYear(),
                "documentType", p.documentType(),
                "establishmentNumber", p.establishmentNumber(),
                "seriesContingencyIndicator", p.seriesContingencyIndicator());

        String jwsSignature = agtSigningService.assinarJWS(dadosAssinatura);

        Map<String, Object> pedido = AgtJson.mapa(
                "schemaVersion", "2.0",
                "submissionUUID", UUID.randomUUID().toString(),
                "taxRegistrationNumber", p.taxRegistrationNumber(),
                "submissionTimeStamp", Instant.now().toString(),
                "softwareInfo", agtSigningService.construirSoftwareInfo(),
                "jwsSignature", jwsSignature,
                "seriesYear", p.seriesYear(),
                "documentType", p.documentType(),
                "establishmentNumber", p.establishmentNumber(),
                "seriesContingencyIndicator", p.seriesContingencyIndicator());

        log.info("Solicitar Série: pedido construído e assinado — submissionUUID={}", pedido.get("submissionUUID"));
        return pedido;
    }

    public record Contexto(String solicitadoPorId, String solicitadoPorNome) {
        public static final Contexto VAZIO = new Contexto(null, null);
    }

    public record ResultadoSolicitacao(Map<String, Object> pedido, Map<String, Object> resposta) {
    }

    /**
     * Constrói e SUBMETE o pedido à AGT (solicitarSerie). Lança
     * AgtRecusadoException (502) se a AGT recusar. Só grava o histórico
     * (AgtSeriesFe) quando a AGT aceitou — AgtSandboxClient já confirmou
     * `seriesFEResult.seriesCode`.
     */
    @Transactional
    public ResultadoSolicitacao solicitarSerie(ParametrosSerie params, Contexto contexto) {
        Map<String, Object> pedido = construirPedidoSerie(params);

        log.info("Solicitar Série: a submeter à AGT — submissionUUID={}", pedido.get("submissionUUID"));
        Map<String, Object> resposta;
        try {
            resposta = agtSandboxClient.solicitarSerie(pedido);
        } catch (AgtApiException erro) {
            log.warn("Solicitar Série: a AGT recusou o pedido — resultCode={} errorList={}", erro.getResultCode(), erro.getErrorList());
            throw new AgtRecusadoException(erro.getMessage(), erro.getEndpoint(), erro.getResultCode(),
                    erro.getErrorList(), erro.getRespostaBruta(), pedido);
        }
        log.info("Solicitar Série: resposta da AGT recebida — submissionUUID={}", pedido.get("submissionUUID"));

        @SuppressWarnings("unchecked")
        Map<String, Object> seriesFEResult = resposta.get("seriesFEResult") instanceof Map
                ? (Map<String, Object>) resposta.get("seriesFEResult") : Map.of();

        AgtSeriesFe linha = new AgtSeriesFe(UUID.randomUUID().toString(), params.seriesYear(), params.documentType(),
                params.establishmentNumber(), params.taxRegistrationNumber(),
                (String) seriesFEResult.get("seriesCode"),
                seriesFEResult.get("authorizedQuantity") != null ? String.valueOf(seriesFEResult.get("authorizedQuantity")) : null,
                seriesFEResult.get("firstDocumentNo") != null ? String.valueOf(seriesFEResult.get("firstDocumentNo")) : null,
                seriesFEResult.get("lastDocumentNo") != null ? String.valueOf(seriesFEResult.get("lastDocumentNo")) : null,
                (String) pedido.get("submissionUUID"),
                resposta.get("requestID") != null ? String.valueOf(resposta.get("requestID")) : null,
                resposta.get("resultCode") != null ? String.valueOf(resposta.get("resultCode")) : "",
                contexto == null ? null : contexto.solicitadoPorId(),
                contexto == null ? null : contexto.solicitadoPorNome(),
                Instant.now());
        repository.save(linha);

        return new ResultadoSolicitacao(pedido, resposta);
    }

    @Transactional(readOnly = true)
    public List<AgtSeriesFe> listarHistorico() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    /** A série ATUALMENTE atribuída pela AGT para um tipo de documento — a mais recente aceite. Nunca inventa um seriesCode. */
    @Transactional(readOnly = true)
    public AgtSeriesFe obterSeriePorTipo(String documentType, int ano, String establishmentNumber) {
        return repository.findFirstByTipoDocumentoAndAnoAndEstablishmentNumberOrderByCreatedAtDesc(
                documentType.trim().toUpperCase(), ano, establishmentNumber).orElse(null);
    }

    /**
     * Atribui, UMA ÚNICA VEZ, o {@code documentNo} REAL da AGT a um
     * documento (FT/NC/RC) — idempotente: se já tem {@code agtDocumentNo}
     * gravado, devolve-o tal qual. {@code SELECT ... FOR UPDATE} bloqueia a
     * linha da série até ao fim da transacção — mesmo mecanismo de
     * FaturacaoService.atribuir() (M3).
     */
    @Transactional
    public String atribuirDocumentNo(String tipo, String id, int ano, String establishmentNumber) {
        String tabela = TABELA_POR_TIPO.get(tipo);
        if (tabela == null) {
            throw new IllegalArgumentException("atribuirDocumentNo: tipo \"" + tipo + "\" não suportado (use FT, NC ou RC).");
        }

        String existente = jdbcTemplate.queryForObject(
                "SELECT \"agt_document_no\" FROM \"" + tabela + "\" WHERE \"id\" = ?", String.class, id);
        if (existente != null) return existente;

        Map<String, Object> linha;
        try {
            linha = jdbcTemplate.queryForMap("""
                    SELECT "id", "series_code", "ultimo_numero"
                      FROM "agtseriesfe"
                     WHERE "tipo_documento" = ? AND "ano" = ? AND "establishment_number" = ?
                     ORDER BY "created_at" DESC
                     LIMIT 1
                     FOR UPDATE
                    """, tipo.toUpperCase(), ano, establishmentNumber);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            linha = null;
        }
        if (linha == null || linha.get("series_code") == null) {
            throw new BusinessRuleException(
                    "Não existe nenhuma série atribuída pela AGT para documentos do tipo \"" + tipo + "\" no ano " + ano + ". "
                            + "Peça a série primeiro (\"Solicitar Série\") antes de emitir ou reenviar este documento.");
        }

        int numero = ((Number) linha.get("ultimo_numero")).intValue() + 1;
        jdbcTemplate.update("UPDATE \"agtseriesfe\" SET \"ultimo_numero\" = ? WHERE \"id\" = ?", numero, linha.get("id"));

        String documentNo = tipo + " " + linha.get("series_code") + "/" + numero;
        jdbcTemplate.update("UPDATE \"" + tabela + "\" SET \"agt_document_no\" = ? WHERE \"id\" = ?", documentNo, id);
        return documentNo;
    }
}
