package ao.kixima.user;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import ao.kixima.company.Company;
import ao.kixima.security.PersonaRole;
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
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Espelha o modelo Prisma `User` (backend/prisma/schema.prisma:498-579,
 * tabela `users`). Núcleo do marco M1 (autenticação/RBAC) — todos os campos
 * escalares estão mapeados; as relações de negócio (purchaseOrdersCreated,
 * notifications, favorites, ...) entram nos marcos que portam esses
 * domínios, nunca aqui.
 */
@Entity
@Table(name = "users")
public class User extends AbstractPersistableEntity<String> {

    /** `text` na base, não `uuid` nativo — ver o mesmo comentário em Company.id. */
    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private PersonaRole role;

    @Column(name = "approval_cap", precision = 14, scale = 2)
    private BigDecimal approvalCap;

    /**
     * Só tem sentido para ADMIN_SISTEMA. VAZIO = Super Admin, sem restrição
     * — valor por omissão, para ninguém que já tenha ADMIN_SISTEMA perder
     * acesso só por este campo existir. Não-vazio = assessor, restrito às
     * áreas listadas (ver ao.kixima.security.AdminArea, M1).
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "admin_areas", nullable = false)
    private List<String> adminAreas = new ArrayList<>();

    @Column(name = "company_id")
    private String companyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", insertable = false, updatable = false)
    private Company company;

    @Column(name = "avatar_url")
    private String avatarUrl;

    private String locale;

    @Column(nullable = false)
    private boolean active = true;

    /** Incrementar invalida imediatamente todos os tokens JWT já emitidos (logout global, troca de senha). */
    @Column(name = "token_version", nullable = false)
    private int tokenVersion = 0;

    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;

    // --- MFA ---------------------------------------------------------------
    @Column(name = "mfa_method")
    private String mfaMethod;

    @Column(name = "totp_secret")
    private String totpSecret;

    @Column(name = "totp_enabled_at")
    private Instant totpEnabledAt;

    @Column(name = "mfa_code_hash")
    private String mfaCodeHash;

    @Column(name = "mfa_code_expira_em")
    private Instant mfaCodeExpiraEm;

    @Column(name = "mfa_code_tentativas", nullable = false)
    private int mfaCodeTentativas = 0;

    @Column(name = "mfa_code_enviado_em")
    private Instant mfaCodeEnviadoEm;

    // --- Bloqueio progressivo ------------------------------------------------
    @Column(name = "falhas_seguidas", nullable = false)
    private int falhasSeguidas = 0;

    @Column(name = "ultima_falha_em")
    private Instant ultimaFalhaEm;

    /** Bloqueio TEMPORÁRIO sempre — nunca permanente (ver comentário no schema Prisma). */
    @Column(name = "bloqueado_ate")
    private Instant bloqueadoAte;

    @Column(name = "aviso_bloqueio_em")
    private Instant avisoBloqueioEm;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @org.hibernate.annotations.UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
        // JPA
    }

    /**
     * Criação de uma conta nova (aceitação de convite — ver
     * ao.kixima.invite.InviteService e ao.kixima.admin.AdminService — ou o
     * administrador criado no cadastro público de empresa, ver
     * ao.kixima.company.CompanyService#registerCompany). Os restantes campos
     * (MFA, bloqueio progressivo, ...) nascem com o valor por omissão
     * declarado acima.
     */
    public User(String id, String name, String email, String passwordHash, PersonaRole role, String companyId,
                boolean active, Instant termsAcceptedAt, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.companyId = companyId;
        this.active = active;
        this.termsAcceptedAt = termsAcceptedAt;
        this.createdAt = createdAt;
    }

    // --- getters/setters -----------------------------------------------

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public PersonaRole getRole() {
        return role;
    }

    public void setRole(PersonaRole role) {
        this.role = role;
    }

    public List<String> getAdminAreas() {
        return adminAreas;
    }

    public void setAdminAreas(List<String> adminAreas) {
        this.adminAreas = adminAreas;
    }

    public String getCompanyId() {
        return companyId;
    }

    public void setCompanyId(String companyId) {
        this.companyId = companyId;
    }

    public String getLocale() {
        return locale;
    }

    public Company getCompany() {
        return company;
    }

    public boolean isActive() {
        return active;
    }

    public void setApprovalCap(BigDecimal approvalCap) {
        this.approvalCap = approvalCap;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    public void setTokenVersion(int tokenVersion) {
        this.tokenVersion = tokenVersion;
    }

    public int getFalhasSeguidas() {
        return falhasSeguidas;
    }

    public void setFalhasSeguidas(int falhasSeguidas) {
        this.falhasSeguidas = falhasSeguidas;
    }

    public Instant getUltimaFalhaEm() {
        return ultimaFalhaEm;
    }

    public void setUltimaFalhaEm(Instant ultimaFalhaEm) {
        this.ultimaFalhaEm = ultimaFalhaEm;
    }

    public Instant getBloqueadoAte() {
        return bloqueadoAte;
    }

    public void setBloqueadoAte(Instant bloqueadoAte) {
        this.bloqueadoAte = bloqueadoAte;
    }

    public Instant getAvisoBloqueioEm() {
        return avisoBloqueioEm;
    }

    public void setAvisoBloqueioEm(Instant avisoBloqueioEm) {
        this.avisoBloqueioEm = avisoBloqueioEm;
    }

    public String getMfaMethod() {
        return mfaMethod;
    }

    public void setMfaMethod(String mfaMethod) {
        this.mfaMethod = mfaMethod;
    }

    public String getTotpSecret() {
        return totpSecret;
    }

    public void setTotpSecret(String totpSecret) {
        this.totpSecret = totpSecret;
    }

    public Instant getTotpEnabledAt() {
        return totpEnabledAt;
    }

    public void setTotpEnabledAt(Instant totpEnabledAt) {
        this.totpEnabledAt = totpEnabledAt;
    }

    public String getMfaCodeHash() {
        return mfaCodeHash;
    }

    public void setMfaCodeHash(String mfaCodeHash) {
        this.mfaCodeHash = mfaCodeHash;
    }

    public Instant getMfaCodeExpiraEm() {
        return mfaCodeExpiraEm;
    }

    public void setMfaCodeExpiraEm(Instant mfaCodeExpiraEm) {
        this.mfaCodeExpiraEm = mfaCodeExpiraEm;
    }

    public int getMfaCodeTentativas() {
        return mfaCodeTentativas;
    }

    public void setMfaCodeTentativas(int mfaCodeTentativas) {
        this.mfaCodeTentativas = mfaCodeTentativas;
    }

    public Instant getMfaCodeEnviadoEm() {
        return mfaCodeEnviadoEm;
    }

    public void setMfaCodeEnviadoEm(Instant mfaCodeEnviadoEm) {
        this.mfaCodeEnviadoEm = mfaCodeEnviadoEm;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public BigDecimal getApprovalCap() {
        return approvalCap;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public Instant getTermsAcceptedAt() {
        return termsAcceptedAt;
    }
}
