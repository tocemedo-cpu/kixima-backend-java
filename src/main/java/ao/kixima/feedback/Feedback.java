package ao.kixima.feedback;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import ao.kixima.company.Company;
import ao.kixima.user.User;
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

import java.time.Instant;

/**
 * Espelha o modelo Prisma `Feedback` (schema.prisma:1965-1985, tabela
 * `feedback`) — avaliação pública da plataforma (homepage corporativa,
 * secção "Avaliações"). {@code verified} é sempre {@code true} nesta
 * versão: só quem tem sessão e empresa reais consegue submeter.
 */
@Entity
@Table(name = "feedback")
public class Feedback extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User user;

    @Column(name = "company_id", nullable = false)
    private String companyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", insertable = false, updatable = false)
    private Company company;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private FeedbackCategoria categoria;

    @Column(name = "target_id")
    private String targetId;

    @Column(name = "target_label")
    private String targetLabel;

    @Column(nullable = false)
    private int rating;

    @Column(nullable = false)
    private String message;

    @Column(nullable = false)
    private boolean verified = true;

    @Column(nullable = false)
    private boolean approved = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Feedback() {
        // JPA
    }

    public Feedback(String id, String userId, String companyId, FeedbackCategoria categoria, String targetId,
                     String targetLabel, int rating, String message, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.companyId = companyId;
        this.categoria = categoria;
        this.targetId = targetId;
        this.targetLabel = targetLabel;
        this.rating = rating;
        this.message = message;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public User getUser() {
        return user;
    }

    public String getCompanyId() {
        return companyId;
    }

    public Company getCompany() {
        return company;
    }

    public FeedbackCategoria getCategoria() {
        return categoria;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getTargetLabel() {
        return targetLabel;
    }

    public int getRating() {
        return rating;
    }

    public String getMessage() {
        return message;
    }

    public boolean isVerified() {
        return verified;
    }

    public boolean isApproved() {
        return approved;
    }

    public void setApproved(boolean approved) {
        this.approved = approved;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
