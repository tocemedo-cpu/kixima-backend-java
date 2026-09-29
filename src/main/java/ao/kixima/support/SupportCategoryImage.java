package ao.kixima.support;

import org.hibernate.annotations.UpdateTimestamp;
import ao.kixima.common.persistence.AbstractPersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Espelha o modelo `SupportCategoryImage` — a imagem carregada pelo Admin para um local da página de Ajuda. */
@Entity
@Table(name = "support_category_images")
public class SupportCategoryImage extends AbstractPersistableEntity<String> {

    @Id
    private String key;

    @Column(name = "image_url", nullable = false)
    private String imageUrl;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SupportCategoryImage() {
        // JPA
    }

    public SupportCategoryImage(String key, String imageUrl, Instant updatedAt) {
        this.key = key;
        this.imageUrl = imageUrl;
        this.updatedAt = updatedAt;
    }

    @Override
    public String getId() {
        return key;
    }

    public String getKey() {
        return key;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
        this.updatedAt = Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
