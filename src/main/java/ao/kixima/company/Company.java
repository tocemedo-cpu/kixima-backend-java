package ao.kixima.company;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import ao.kixima.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Espelha o modelo Prisma `Company` (backend/prisma/schema.prisma:244-347,
 * tabela `companies`).
 *
 * ÂMBITO NESTE MARCO (M0/M1): só os campos escalares e a relação `users`,
 * necessários para autenticação/RBAC. As restantes ~20 relações do modelo
 * Prisma (productsOffered, purchaseOrdersMade/Recv, contracts*, policies,
 * ...) entram entidade a entidade nos marcos M2-M5, à medida que cada
 * domínio é portado — nunca `fetch = EAGER`, sempre mapeadas explicitamente
 * (ver plano, secção 2: "cada resposta JSON é construída por um mapper
 * explícito, nunca por serializar o grafo da entidade directamente").
 */
@Entity
@Table(name = "companies")
public class Company extends AbstractPersistableEntity<String> {

    /**
     * `text` na base, não `uuid` nativo — confirmado por inspecção directa
     * de `\d companies` na base de teste local (M0, verificação obrigatória
     * do plano, secção 2): o Prisma gera o UUID do lado do cliente e o
     * guarda como texto simples, não usa o tipo `uuid` do Postgres nem
     * `gen_random_uuid()`. Java tem de gerar o valor da mesma forma, nunca
     * `@GeneratedValue`.
     */
    @Id
    private String id;

    /** Vazio só enquanto o cadastro está em RASCUNHO (preenchido na 1.ª etapa do perfil). */
    private String name;

    /** Vazio só enquanto o cadastro está em RASCUNHO. */
    @Column(name = "tax_id", unique = true)
    private String taxId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private CompanyType type;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private CompanyStatus status = CompanyStatus.PENDENTE;

    @Column(name = "contact_email", nullable = false)
    private String contactEmail;

    @Column(name = "contact_phone")
    private String contactPhone;

    private String address;

    @Column(nullable = false)
    private boolean verified = false;

    @Column(name = "logo_url")
    private String logoUrl;

    private String city;
    private String province;
    private String country = "Angola";

    // `settings Json?` no Prisma — mapeado como texto JSON bruto neste marco;
    // um @Convert dedicado entra quando um domínio precisar de o ler/escrever
    // estruturadamente (M2, "Configurações do Company Admin").
    @JdbcTypeCode(SqlTypes.JSON)
    private String settings;

    @Column(name = "bank_name")
    private String bankName;
    private String iban;
    private String swift;

    @Column(name = "serie_fiscal")
    private String serieFiscal;

    @Column(name = "data_adesao_facturacao_electronica")
    private Instant dataAdesaoFacturacaoElectronica;

    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;

    private Integer employees;

    @Column(name = "annual_revenue_usd", precision = 16, scale = 2)
    private BigDecimal annualRevenueUsd;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private CompanySize size = CompanySize.PEQUENA;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private CompanyPlan plan = CompanyPlan.BASE;

    @Column(name = "search_rank", nullable = false)
    private int searchRank = 0;

    @Column(name = "search_text")
    private String searchText;

    @Column(name = "plano_valido_ate")
    private Instant planoValidoAte;

    @Column(name = "seat_price_usd", nullable = false, precision = 10, scale = 2)
    private BigDecimal seatPriceUsd = new BigDecimal("100");

    @Column(name = "plan_notes")
    private String planNotes;

    @Column(name = "ultimo_aviso_subscricao_tier")
    private String ultimoAvisoSubscricaoTier;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @org.hibernate.annotations.UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    /** Quando o cadastro foi submetido para análise (a última vez). */
    @Column(name = "submitted_at")
    private Instant submittedAt;

    /** Motivo indicado pela KIXIMA na última rejeição — mostrado ao utilizador para corrigir e voltar a submeter. */
    @Column(name = "rejection_reason")
    private String rejectionReason;

    /** Dados do cadastro ainda sem registo próprio (apólice da Prestadora), em JSON. Ver {@code OnboardingService}. */
    @Column(name = "onboarding_draft")
    private String onboardingDraft;

    @OneToMany(mappedBy = "company", fetch = jakarta.persistence.FetchType.LAZY)
    private List<User> users;

    protected Company() {
        // JPA
    }

    /**
     * Criação de uma empresa nova — pelo cadastro público completo
     * ({@link CompanyService#registerCompany}: dimensão, plano, documentos,
     * apólice, administrador) e pela aprovação de um pedido de Supplier
     * Development ({@code ao.kixima.supplierdev.SupplierDevService#approve},
     * que nasce sempre FORNECEDOR/PENDENTE, tal como o Node).
     */
    public Company(String id, String name, String taxId, CompanyType type, String contactEmail, String contactPhone,
                    String province, Integer employees, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.taxId = taxId;
        this.type = type;
        this.contactEmail = contactEmail;
        this.contactPhone = contactPhone;
        this.province = province;
        this.employees = employees;
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

    public String getTaxId() {
        return taxId;
    }

    public void setTaxId(String taxId) {
        this.taxId = taxId;
    }

    public CompanyType getType() {
        return type;
    }

    public void setType(CompanyType type) {
        this.type = type;
    }

    public CompanyStatus getStatus() {
        return status;
    }

    public void setStatus(CompanyStatus status) {
        this.status = status;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail;
    }

    public boolean isVerified() {
        return verified;
    }

    public void setVerified(boolean verified) {
        this.verified = verified;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public String getAddress() {
        return address;
    }

    public String getProvince() {
        return province;
    }

    public BigDecimal getSeatPriceUsd() {
        return seatPriceUsd;
    }

    public String getCity() {
        return city;
    }

    public String getCountry() {
        return country;
    }

    public String getSerieFiscal() {
        return serieFiscal;
    }

    public Instant getDataAdesaoFacturacaoElectronica() {
        return dataAdesaoFacturacaoElectronica;
    }

    public CompanySize getSize() {
        return size;
    }

    public void setSize(CompanySize size) {
        this.size = size;
    }

    public CompanyPlan getPlan() {
        return plan;
    }

    public void setPlan(CompanyPlan plan) {
        this.plan = plan;
    }

    public Instant getPlanoValidoAte() {
        return planoValidoAte;
    }

    public void setPlanoValidoAte(Instant planoValidoAte) {
        this.planoValidoAte = planoValidoAte;
    }

    public String getUltimoAvisoSubscricaoTier() {
        return ultimoAvisoSubscricaoTier;
    }

    public void setUltimoAvisoSubscricaoTier(String ultimoAvisoSubscricaoTier) {
        this.ultimoAvisoSubscricaoTier = ultimoAvisoSubscricaoTier;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<User> getUsers() {
        return users;
    }

    // --- acessores acrescentados no fecho de lacunas B.1 (cadastro/due diligence) ---

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getSettings() {
        return settings;
    }

    public void setSettings(String settings) {
        this.settings = settings;
    }

    public String getBankName() {
        return bankName;
    }

    public String getIban() {
        return iban;
    }

    public String getSwift() {
        return swift;
    }

    public void setDadosBancarios(String bankName, String iban, String swift) {
        this.bankName = bankName;
        this.iban = iban;
        this.swift = swift;
    }

    public void setSerieFiscal(String serieFiscal) {
        this.serieFiscal = serieFiscal;
    }

    public void setDataAdesaoFacturacaoElectronica(Instant dataAdesaoFacturacaoElectronica) {
        this.dataAdesaoFacturacaoElectronica = dataAdesaoFacturacaoElectronica;
    }

    public Instant getTermsAcceptedAt() {
        return termsAcceptedAt;
    }

    public void setTermsAcceptedAt(Instant termsAcceptedAt) {
        this.termsAcceptedAt = termsAcceptedAt;
    }

    public Integer getEmployees() {
        return employees;
    }

    public void setEmployees(Integer employees) {
        this.employees = employees;
    }

    public BigDecimal getAnnualRevenueUsd() {
        return annualRevenueUsd;
    }

    public void setAnnualRevenueUsd(BigDecimal annualRevenueUsd) {
        this.annualRevenueUsd = annualRevenueUsd;
    }

    public int getSearchRank() {
        return searchRank;
    }

    public void setSearchRank(int searchRank) {
        this.searchRank = searchRank;
    }

    public void setSeatPriceUsd(BigDecimal seatPriceUsd) {
        this.seatPriceUsd = seatPriceUsd;
    }

    public String getPlanNotes() {
        return planNotes;
    }

    public void setPlanNotes(String planNotes) {
        this.planNotes = planNotes;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getRejectedAt() {
        return rejectedAt;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public void setProvince(String province) {
        this.province = province;
    }

    public void setLogoUrl(String logoUrl) {
        this.logoUrl = logoUrl;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public String getOnboardingDraft() {
        return onboardingDraft;
    }

    public void setOnboardingDraft(String onboardingDraft) {
        this.onboardingDraft = onboardingDraft;
    }

    /** O perfil ainda pode ser editado pelo utilizador: em preenchimento, ou rejeitado e à espera de correcção. */
    public boolean editavelNoCadastro() {
        return status == CompanyStatus.RASCUNHO || status == CompanyStatus.REJEITADA;
    }

    /** Submissão para análise da KIXIMA: PENDENTE, e o motivo de uma rejeição anterior deixa de se aplicar. */
    public void submeter(Instant agora) {
        this.status = CompanyStatus.PENDENTE;
        this.submittedAt = agora;
        this.rejectionReason = null;
        this.rejectedAt = null;
    }

    /** `decideCompanyStatus`: APROVADA + approvedAt, ou REJEITADA + rejectedAt (+ motivo, quando indicado). */
    public void decidir(boolean approve, Instant agora) {
        decidir(approve, agora, null);
    }

    public void decidir(boolean approve, Instant agora, String motivo) {
        if (approve) {
            this.status = CompanyStatus.APROVADA;
            this.approvedAt = agora;
            this.rejectionReason = null;
        } else {
            this.status = CompanyStatus.REJEITADA;
            this.rejectedAt = agora;
            this.rejectionReason = motivo == null || motivo.isBlank() ? null : motivo.trim();
        }
    }
}
