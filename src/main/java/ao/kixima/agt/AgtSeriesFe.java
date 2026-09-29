package ao.kixima.agt;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Espelha o modelo Prisma `AgtSeriesFe` (schema.prisma, tabela
 * `agtseriesfe`) — histórico de pedidos "Solicitar Série" aceites pela AGT
 * + o contador atómico do último número de documento atribuído dentro de
 * cada série (ver AgtSeriesService.atribuirDocumentNo).
 */
@Entity
@Table(name = "agtseriesfe")
public class AgtSeriesFe extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(nullable = false)
    private int ano;

    @Column(name = "tipo_documento", nullable = false)
    private String tipoDocumento;

    @Column(name = "establishment_number", nullable = false)
    private String establishmentNumber;

    @Column(name = "tax_registration_number", nullable = false)
    private String taxRegistrationNumber;

    @Column(name = "series_code")
    private String seriesCode;

    @Column(name = "authorized_quantity")
    private String authorizedQuantity;

    @Column(name = "first_document_no")
    private String firstDocumentNo;

    @Column(name = "last_document_no")
    private String lastDocumentNo;

    @Column(name = "submission_uuid", nullable = false)
    private String submissionUUID;

    @Column(name = "request_id")
    private String requestID;

    @Column(name = "result_code", nullable = false)
    private String resultCode;

    @Column(name = "solicitado_por_id")
    private String solicitadoPorId;

    @Column(name = "solicitado_por_nome")
    private String solicitadoPorNome;

    @Column(name = "ultimo_numero", nullable = false)
    private int ultimoNumero = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AgtSeriesFe() {
        // JPA
    }

    public AgtSeriesFe(String id, int ano, String tipoDocumento, String establishmentNumber, String taxRegistrationNumber,
                        String seriesCode, String authorizedQuantity, String firstDocumentNo, String lastDocumentNo,
                        String submissionUUID, String requestID, String resultCode, String solicitadoPorId,
                        String solicitadoPorNome, Instant createdAt) {
        this.id = id;
        this.ano = ano;
        this.tipoDocumento = tipoDocumento;
        this.establishmentNumber = establishmentNumber;
        this.taxRegistrationNumber = taxRegistrationNumber;
        this.seriesCode = seriesCode;
        this.authorizedQuantity = authorizedQuantity;
        this.firstDocumentNo = firstDocumentNo;
        this.lastDocumentNo = lastDocumentNo;
        this.submissionUUID = submissionUUID;
        this.requestID = requestID;
        this.resultCode = resultCode;
        this.solicitadoPorId = solicitadoPorId;
        this.solicitadoPorNome = solicitadoPorNome;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public int getAno() {
        return ano;
    }

    public String getTipoDocumento() {
        return tipoDocumento;
    }

    public String getEstablishmentNumber() {
        return establishmentNumber;
    }

    public String getTaxRegistrationNumber() {
        return taxRegistrationNumber;
    }

    public String getSeriesCode() {
        return seriesCode;
    }

    public String getAuthorizedQuantity() {
        return authorizedQuantity;
    }

    public String getFirstDocumentNo() {
        return firstDocumentNo;
    }

    public String getLastDocumentNo() {
        return lastDocumentNo;
    }

    public String getSubmissionUUID() {
        return submissionUUID;
    }

    public String getRequestID() {
        return requestID;
    }

    public String getResultCode() {
        return resultCode;
    }

    public String getSolicitadoPorId() {
        return solicitadoPorId;
    }

    public String getSolicitadoPorNome() {
        return solicitadoPorNome;
    }

    public int getUltimoNumero() {
        return ultimoNumero;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
