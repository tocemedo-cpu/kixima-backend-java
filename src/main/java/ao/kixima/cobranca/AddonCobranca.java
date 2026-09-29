package ao.kixima.cobranca;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import ao.kixima.company.Company;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/** Espelha `model AddonCobranca` (tabela addon_cobrancas) — a conta de um add-on pago (hoje só o PO Robot). */
@Entity
@Table(name = "addon_cobrancas")
public class AddonCobranca extends AbstractPersistableEntity<String> {

    @Id
    @Column(nullable = false)
    private String id;

    @Column(nullable = false, unique = true)
    private String referencia;

    @Column(name = "company_id", nullable = false)
    private String companyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", insertable = false, updatable = false)
    private Company company;

    @Column(name = "addon_key", nullable = false)
    private String addonKey;

    @Column(name = "valor_usd", nullable = false, precision = 12, scale = 2)
    private BigDecimal valorUsd;

    @Column(nullable = false)
    private String periodo;

    @Column(nullable = false)
    private int meses;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private CobrancaStatus status = CobrancaStatus.PENDENTE;

    @Column(name = "comprovativo_url")
    private String comprovativoUrl;

    @Column(name = "submetido_em")
    private Instant submetidoEm;

    @Column(name = "confirmada_por")
    private String confirmadaPor;

    @Column(name = "confirmada_em")
    private Instant confirmadaEm;

    @Column(name = "valido_ate")
    private Instant validoAte;

    @Column
    private String notas;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private CanalCobranca canal = CanalCobranca.TRANSFERENCIA_MANUAL;

    @Column(name = "referencia_externa")
    private String referenciaExterna;

    @Column
    private String telemovel;

    @Column(name = "created_by_id")
    private String createdById;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AddonCobranca() {
    }

    public AddonCobranca(String id, String referencia, String companyId, String addonKey, BigDecimal valorUsd, String periodo,
                         int meses, String createdById, Instant createdAt) {
        this.id = id;
        this.referencia = referencia;
        this.companyId = companyId;
        this.addonKey = addonKey;
        this.valorUsd = valorUsd;
        this.periodo = periodo;
        this.meses = meses;
        this.createdById = createdById;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public boolean emAberto() {
        return status == CobrancaStatus.PENDENTE || status == CobrancaStatus.COMPROVATIVO_ENVIADO;
    }

    public void registarComprovativo(String url, Instant quando) {
        this.comprovativoUrl = url;
        this.status = CobrancaStatus.COMPROVATIVO_ENVIADO;
        this.submetidoEm = quando;
    }

    public void confirmar(Instant quando, Instant validoAte, String confirmadaPor, String notas) {
        this.status = CobrancaStatus.CONFIRMADA;
        this.confirmadaEm = quando;
        this.validoAte = validoAte;
        this.confirmadaPor = confirmadaPor;
        if (notas != null) this.notas = notas;
    }

    public void cancelar(String motivo) {
        this.status = CobrancaStatus.CANCELADA;
        this.notas = motivo;
    }

    @Override
    public String getId() {
        return id;
    }

    public String getReferencia() {
        return referencia;
    }

    public String getCompanyId() {
        return companyId;
    }

    public Company getCompany() {
        return company;
    }

    public String getAddonKey() {
        return addonKey;
    }

    public BigDecimal getValorUsd() {
        return valorUsd;
    }

    public String getPeriodo() {
        return periodo;
    }

    public int getMeses() {
        return meses;
    }

    public CobrancaStatus getStatus() {
        return status;
    }

    public String getComprovativoUrl() {
        return comprovativoUrl;
    }

    public Instant getSubmetidoEm() {
        return submetidoEm;
    }

    public String getConfirmadaPor() {
        return confirmadaPor;
    }

    public Instant getConfirmadaEm() {
        return confirmadaEm;
    }

    public Instant getValidoAte() {
        return validoAte;
    }

    public String getNotas() {
        return notas;
    }

    public CanalCobranca getCanal() {
        return canal;
    }

    public String getReferenciaExterna() {
        return referenciaExterna;
    }

    public String getTelemovel() {
        return telemovel;
    }

    public String getCreatedById() {
        return createdById;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
