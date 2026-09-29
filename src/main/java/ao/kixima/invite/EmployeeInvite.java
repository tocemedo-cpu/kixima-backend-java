package ao.kixima.invite;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import ao.kixima.security.PersonaRole;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Espelha o modelo Prisma `EmployeeInvite` (schema.prisma:414-434, tabela
 * `employee_invites`) — convite de funcionário para uma empresa (via
 * {@code companyId}), ou de assessor {@code ADMIN_SISTEMA} (companyId
 * {@code null}, usa {@code adminAreas}).
 *
 * Os dois fluxos escrevem nesta mesma tabela: o convite de funcionário em
 * {@link InviteService} (companyId preenchido, adminAreas vazio) e o convite
 * de assessor ADMIN_SISTEMA em {@code ao.kixima.admin.AdminService}
 * (createAdminInvite/listAdminInvites/resend/cancel/accept, rotas em
 * {@code AdminController}), que é o único a preencher {@code adminAreas}.
 */
@Entity
@Table(name = "employee_invites")
public class EmployeeInvite extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "company_id")
    private String companyId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private PersonaRole role;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "admin_areas", nullable = false)
    private List<String> adminAreas = new ArrayList<>();

    @Column(nullable = false, unique = true)
    private String token;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private InviteStatus status = InviteStatus.PENDENTE;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "created_by_id")
    private String createdById;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EmployeeInvite() {
        // JPA
    }

    public EmployeeInvite(String id, String companyId, String name, String email, PersonaRole role, String token,
                           Instant expiresAt, String createdById, Instant createdAt) {
        this.id = id;
        this.companyId = companyId;
        this.name = name;
        this.email = email;
        this.role = role;
        this.token = token;
        this.expiresAt = expiresAt;
        this.createdById = createdById;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getCompanyId() {
        return companyId;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public PersonaRole getRole() {
        return role;
    }

    public String getToken() {
        return token;
    }

    public List<String> getAdminAreas() {
        return adminAreas;
    }

    /** Só o convite de assessor ADMIN_SISTEMA as usa (adminService.js) — os convites de funcionário ficam com a lista vazia. */
    public void setAdminAreas(List<String> adminAreas) {
        this.adminAreas = adminAreas == null ? new ArrayList<>() : new ArrayList<>(adminAreas);
    }

    public void setToken(String token) {
        this.token = token;
    }

    public InviteStatus getStatus() {
        return status;
    }

    public void setStatus(InviteStatus status) {
        this.status = status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(Instant acceptedAt) {
        this.acceptedAt = acceptedAt;
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
