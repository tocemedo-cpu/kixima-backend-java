package ao.kixima.agt;

import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Espelha backend/src/services/agtSandboxSubmissionService.js — liga
 * AgtSandboxClient (cliente REST, só transporte) à emissão REAL de
 * faturas: sem isto, o cliente estava pronto e testado mas nunca era
 * chamado por nenhum serviço (achado N10 da auditoria de arquitetura,
 * citado no PLANO.md).
 *
 * REAPROVEITA AgtPayloadService.construirPayload() para os campos comuns e
 * a verificação de posse; assina de novo com o esquema PRÓPRIO da Sandbox
 * (pipe-delimited, AgtSandboxClient) em vez do esquema v2.0 (JSON) que
 * construirPayload gera para o outro consumidor (GET /agt-payload).
 *
 * MESMO PRINCÍPIO "RECUSA-SE A FINGIR": sem a Sandbox configurada, esta
 * função não faz nada — não é um erro, é "ainda não ligado".
 *
 * NUNCA LANÇA: chamada sempre depois de o documento fiscal já ter sido
 * criado e COMITADO (ver PoService.acceptPurchaseOrder — hook
 * afterCommit) — uma falha aqui fica em auditoria, nunca desfaz nem
 * bloqueia a emissão já efectivada.
 */
@Service
public class AgtSandboxSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(AgtSandboxSubmissionService.class);

    private static final Map<String, String> ENTIDADE_POR_TIPO = Map.of("FT", "Invoice", "NC", "CreditNote", "RC", "Payment");

    private final AgtSandboxClient agtSandboxClient;
    private final AgtPayloadService agtPayloadService;
    private final AuditService auditService;

    public AgtSandboxSubmissionService(AgtSandboxClient agtSandboxClient, AgtPayloadService agtPayloadService, AuditService auditService) {
        this.agtSandboxClient = agtSandboxClient;
        this.agtPayloadService = agtPayloadService;
        this.auditService = auditService;
    }

    /** Submete um documento (hoje só FT — ver AgtPayloadService) já emitido à Sandbox da AGT. */
    @SuppressWarnings("unchecked")
    public void submeter(String tipo, String id, String supplierCompanyId) {
        if (!agtSandboxClient.disponivel()) return; // ainda por configurar — silencioso, não é falha.

        String entityType = ENTIDADE_POR_TIPO.getOrDefault(tipo, tipo);
        try {
            Map<String, Object> envelopeV20 = agtPayloadService.construirPayload(tipo, id, supplierCompanyId);
            Map<String, Object> doc = ((java.util.List<Map<String, Object>>) envelopeV20.get("documents")).get(0);
            String taxRegistrationNumber = (String) envelopeV20.get("taxRegistrationNumber");

            AgtSandboxClient.SoftwareAssinado software = agtSandboxClient.assinarSoftware();
            String jwsDocumentSignature = agtSandboxClient.assinarDocumento(
                    (String) doc.get("documentNo"), taxRegistrationNumber, (String) doc.get("documentType"),
                    (String) doc.get("documentDate"), (String) doc.get("customerTaxID"), (String) doc.get("customerCountry"),
                    (String) doc.get("companyName"), doc.get("documentTotals"));
            String submissionUUID = UUID.randomUUID().toString();
            String jwsSignature = agtSandboxClient.assinarSolicitacao(taxRegistrationNumber, submissionUUID);

            Map<String, Object> documento = AgtJson.mapa(
                    "documentNo", doc.get("documentNo"),
                    "documentType", doc.get("documentType"),
                    "documentDate", doc.get("documentDate"),
                    "taxRegistrationNumber", taxRegistrationNumber,
                    "customerTaxID", doc.get("customerTaxID"),
                    "customerCountry", doc.get("customerCountry"),
                    "companyName", doc.get("companyName"),
                    "documentTotals", doc.get("documentTotals"),
                    "softwareInfoDetail", software.softwareInfoDetail(),
                    "jwsSoftwareSignature", software.jwsSoftwareSignature(),
                    "jwsDocumentSignature", jwsDocumentSignature,
                    "submissionUUID", submissionUUID,
                    "jwsSignature", jwsSignature);

            Map<String, Object> resposta = agtSandboxClient.registarFactura(documento);

            auditService.recordSafe(new AuditService.Entry(Actor.anonimo(null), "AGT_SANDBOX_SUBMETIDO", entityType, id, null,
                    AgtJson.mapa("tipo", tipo, "documentNo", doc.get("documentNo"), "submissionUUID", submissionUUID,
                            "resultCode", resposta == null ? null : resposta.get("resultCode"))));
        } catch (Exception err) {
            log.warn("AGT_SANDBOX_FALHOU tipo={} id={}: {}", tipo, id, err.getMessage());
            auditService.recordSafe(new AuditService.Entry(Actor.anonimo(null), "AGT_SANDBOX_FALHOU", entityType, id, null,
                    AgtJson.mapa("tipo", tipo, "erro", err.getMessage())));
        }
    }
}
