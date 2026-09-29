package ao.kixima.supplierdev;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Espelha o modelo Prisma `SupplierDevRequest` (schema.prisma:1886-1923,
 * tabela `supplier_dev_requests`) — candidatura ao programa Supplier
 * Development (emancipação burocrática + parcerias internacionais). A
 * candidatura é PÚBLICA — pode vir de uma empresa ainda não registada
 * ({@code companyId} null).
 */
@Entity
@Table(name = "supplier_dev_requests")
public class SupplierDevRequest extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(name = "company_id")
    private String companyId;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(name = "tax_id")
    private String taxId;

    @Column(name = "contact_name", nullable = false)
    private String contactName;

    @Column(name = "contact_email", nullable = false)
    private String contactEmail;

    @Column(name = "contact_phone")
    private String contactPhone;

    private String province;
    private String sector;
    private Integer employees;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private SupplierDevTrack track = SupplierDevTrack.AMBOS;

    private String needs;

    @Column(name = "access_fee_usd", precision = 10, scale = 2)
    private BigDecimal accessFeeUsd;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "fee_status", nullable = false)
    private PlatformFeeStatus feeStatus = PlatformFeeStatus.PENDENTE;

    @Column(name = "fee_paid_at")
    private Instant feePaidAt;

    @Column(name = "program_fee_usd", precision = 12, scale = 2)
    private BigDecimal programFeeUsd;

    @Column(name = "custom_pricing", nullable = false)
    private boolean customPricing = true;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private SupplierDevStatus status = SupplierDevStatus.RECEBIDA;

    @Column(name = "admin_notes")
    private String adminNotes;

    @Column(name = "handled_by_id")
    private String handledById;

    @Column(name = "handled_at")
    private Instant handledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SupplierDevRequest() {
        // JPA
    }

    public SupplierDevRequest(String id, String reference, String companyId, String companyName, String taxId,
                               String contactName, String contactEmail, String contactPhone, String province,
                               String sector, Integer employees, SupplierDevTrack track, String needs,
                               BigDecimal accessFeeUsd, Instant createdAt) {
        this.id = id;
        this.reference = reference;
        this.companyId = companyId;
        this.companyName = companyName;
        this.taxId = taxId;
        this.contactName = contactName;
        this.contactEmail = contactEmail;
        this.contactPhone = contactPhone;
        this.province = province;
        this.sector = sector;
        this.employees = employees;
        this.track = track == null ? SupplierDevTrack.AMBOS : track;
        this.needs = needs;
        this.accessFeeUsd = accessFeeUsd;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public String getCompanyId() {
        return companyId;
    }

    public void setCompanyId(String companyId) {
        this.companyId = companyId;
    }

    public String getCompanyName() {
        return companyName;
    }

    public String getTaxId() {
        return taxId;
    }

    public String getContactName() {
        return contactName;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public String getProvince() {
        return province;
    }

    public String getSector() {
        return sector;
    }

    public Integer getEmployees() {
        return employees;
    }

    public SupplierDevTrack getTrack() {
        return track;
    }

    public String getNeeds() {
        return needs;
    }

    public BigDecimal getAccessFeeUsd() {
        return accessFeeUsd;
    }

    public PlatformFeeStatus getFeeStatus() {
        return feeStatus;
    }

    public void setFeeStatus(PlatformFeeStatus feeStatus) {
        this.feeStatus = feeStatus;
    }

    public Instant getFeePaidAt() {
        return feePaidAt;
    }

    public void setFeePaidAt(Instant feePaidAt) {
        this.feePaidAt = feePaidAt;
    }

    public BigDecimal getProgramFeeUsd() {
        return programFeeUsd;
    }

    public void setProgramFeeUsd(BigDecimal programFeeUsd) {
        this.programFeeUsd = programFeeUsd;
    }

    public boolean isCustomPricing() {
        return customPricing;
    }

    public void setCustomPricing(boolean customPricing) {
        this.customPricing = customPricing;
    }

    public SupplierDevStatus getStatus() {
        return status;
    }

    public void setStatus(SupplierDevStatus status) {
        this.status = status;
    }

    public String getAdminNotes() {
        return adminNotes;
    }

    public void setAdminNotes(String adminNotes) {
        this.adminNotes = adminNotes;
    }

    public String getHandledById() {
        return handledById;
    }

    public void setHandledById(String handledById) {
        this.handledById = handledById;
    }

    public Instant getHandledAt() {
        return handledAt;
    }

    public void setHandledAt(Instant handledAt) {
        this.handledAt = handledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
