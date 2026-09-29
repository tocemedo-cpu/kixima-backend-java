package ao.kixima.company;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Espelha o modelo Prisma `CompanyDocument` (schema.prisma:485-496, tabela
 * `company_documents`) — documento de credenciamento (Certidão Comercial,
 * Alvará, Licença ANPG) enviado no cadastro público da empresa — escrito por
 * {@link CompanyService#registerCompany}, depois de os ficheiros irem para o
 * storage; lido pela listagem do módulo de Documentação do fornecedor (ver
 * ao.kixima.catalog.CatalogController#documentosDoFornecedor).
 */
@Entity
@Table(name = "company_documents")
public class CompanyDocument extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "company_id", nullable = false)
    private String companyId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private DocumentType type;

    @Column(name = "file_url", nullable = false)
    private String fileUrl;

    @Column(name = "original_name", nullable = false)
    private String originalName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CompanyDocument() {
        // JPA
    }

    public CompanyDocument(String id, String companyId, DocumentType type, String fileUrl, String originalName, Instant createdAt) {
        this.id = id;
        this.companyId = companyId;
        this.type = type;
        this.fileUrl = fileUrl;
        this.originalName = originalName;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getCompanyId() {
        return companyId;
    }

    public DocumentType getType() {
        return type;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public String getOriginalName() {
        return originalName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
