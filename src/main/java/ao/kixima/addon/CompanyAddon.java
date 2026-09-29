package ao.kixima.addon;

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

import java.time.Instant;

/**
 * Espelha o modelo Prisma `CompanyAddon` (schema.prisma:164-182, tabela
 * `company_addons`) — um add-on pago (ex.: {@code PO_ROBOT}) ligado a uma
 * empresa. SÓ LEITURA neste marco: o único sítio onde um add-on se torna
 * ATIVO é a confirmação da cobrança (addonService.aplicarConfirmacao), que
 * continua no Node — ver {@link AddonService}.
 */
@Entity
@Table(name = "company_addons")
public class CompanyAddon extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "company_id", nullable = false)
    private String companyId;

    @Column(name = "addon_key", nullable = false)
    private String addonKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private CompanyAddonStatus status = CompanyAddonStatus.INATIVO;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "valido_ate")
    private Instant validoAte;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CompanyAddon() {
        // JPA
    }

    public CompanyAddon(String id, String companyId, String addonKey) {
        this.id = id;
        this.companyId = companyId;
        this.addonKey = addonKey;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** `companyAddon.upsert` de addonService.aplicarConfirmacao — ATIVO com a validade paga (a primeira confirmação cria, as seguintes renovam). */
    public void ativar(Instant activatedAt, Instant validoAte) {
        this.status = CompanyAddonStatus.ATIVO;
        this.activatedAt = activatedAt;
        this.validoAte = validoAte;
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getCompanyId() {
        return companyId;
    }

    public String getAddonKey() {
        return addonKey;
    }

    public CompanyAddonStatus getStatus() {
        return status;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public Instant getValidoAte() {
        return validoAte;
    }
}
