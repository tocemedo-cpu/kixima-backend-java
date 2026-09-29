package ao.kixima.conversation;

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
 * Espelha o modelo Prisma `Conversation` (schema.prisma, tabela
 * `conversations`) — Chat Comercial, Comprador ↔ Fornecedor/Prestador.
 * {@code contextType} guarda exactamente as chaves usadas em
 * ConversationService (minúsculas — "product", "purchase_order", "quote",
 * "contract"), não os nomes PascalCase do comentário no schema Prisma:
 * é o que o código do Node realmente grava, não o que o comentário
 * documenta.
 */
@Entity
@Table(name = "conversations")
public class Conversation extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "buyer_company_id", nullable = false)
    private String buyerCompanyId;

    @Column(name = "supplier_company_id", nullable = false)
    private String supplierCompanyId;

    @Column(name = "context_type")
    private String contextType;

    @Column(name = "context_id")
    private String contextId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private ConversationStatus status = ConversationStatus.ABERTA;

    @Column(name = "created_by_id", nullable = false)
    private String createdById;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Conversation() {
        // JPA
    }

    public Conversation(String id, String buyerCompanyId, String supplierCompanyId, String contextType,
                         String contextId, String createdById, Instant createdAt) {
        this.id = id;
        this.buyerCompanyId = buyerCompanyId;
        this.supplierCompanyId = supplierCompanyId;
        this.contextType = contextType;
        this.contextId = contextId;
        this.createdById = createdById;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getBuyerCompanyId() {
        return buyerCompanyId;
    }

    public String getSupplierCompanyId() {
        return supplierCompanyId;
    }

    public String getContextType() {
        return contextType;
    }

    public String getContextId() {
        return contextId;
    }

    public ConversationStatus getStatus() {
        return status;
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

    /**
     * "Toca" a conversa (nova mensagem) — espelha o
     * {@code prisma.conversation.update({data:{updatedAt: new Date()}})}
     * explícito do Node. {@code @UpdateTimestamp} só regenera o valor
     * quando HÁ um UPDATE a acontecer (dirty checking); sem nenhum outro
     * campo alterado, marcar este campo como sujo é o que força esse
     * UPDATE a existir — o valor final ainda vem do gerador, este só
     * garante que ele corre.
     */
    public void tocarAtualizacao() {
        this.updatedAt = Instant.now();
    }
}
