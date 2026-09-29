package ao.kixima.apikey;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Espelha o modelo Prisma `ApiKey` (schema.prisma:47-63, tabela
 * `api_keys`) — chave de acesso à API pública de catálogo (plano Pro, ver
 * {@link ApiKeyService}). Só o {@code hash} (bcrypt) fica guardado; a
 * chave inteira só existe no momento da criação, nunca mais.
 */
@Entity
@Table(name = "api_keys")
public class ApiKey extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "company_id", nullable = false)
    private String companyId;

    @Column(nullable = false)
    private String nome;

    @Column(nullable = false, unique = true)
    private String prefixo;

    @Column(nullable = false)
    private String hash;

    @Column(name = "criada_por")
    private String criadaPor;

    @Column(name = "ultimo_uso")
    private Instant ultimoUso;

    @Column(name = "revogada_em")
    private Instant revogadaEm;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ApiKey() {
        // JPA
    }

    public ApiKey(String id, String companyId, String nome, String prefixo, String hash, String criadaPor, Instant createdAt) {
        this.id = id;
        this.companyId = companyId;
        this.nome = nome;
        this.prefixo = prefixo;
        this.hash = hash;
        this.criadaPor = criadaPor;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getCompanyId() {
        return companyId;
    }

    public String getNome() {
        return nome;
    }

    public String getPrefixo() {
        return prefixo;
    }

    public String getHash() {
        return hash;
    }

    public String getCriadaPor() {
        return criadaPor;
    }

    public Instant getUltimoUso() {
        return ultimoUso;
    }

    public void setUltimoUso(Instant ultimoUso) {
        this.ultimoUso = ultimoUso;
    }

    public Instant getRevogadaEm() {
        return revogadaEm;
    }

    public void setRevogadaEm(Instant revogadaEm) {
        this.revogadaEm = revogadaEm;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
