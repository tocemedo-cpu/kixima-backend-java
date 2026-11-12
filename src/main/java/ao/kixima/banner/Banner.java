package ao.kixima.banner;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Um banner do carrossel da homepage pública (tabela {@code banners}, migração V3). {@code imagem} é vazio até haver
 * upload; {@code ordem} é 1..n e é mantida pelo {@link BannerService}.
 */
@Entity
@Table(name = "banners")
public class Banner extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column
    private String etiqueta;

    @Column(nullable = false)
    private String titulo;

    @Column(nullable = false)
    private String texto;

    @Column(nullable = false)
    private String cta;

    @Column(nullable = false)
    private String href;

    @Column
    private String cta2;

    @Column
    private String href2;

    @Column(nullable = false)
    private String imagem = "";

    @Column(nullable = false)
    private boolean ativo;

    @Column(nullable = false)
    private int ordem;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Banner() {
        // JPA
    }

    public Banner(String id, int ordem, Instant createdAt) {
        this.id = id;
        this.ordem = ordem;
        this.createdAt = createdAt;
    }

    @Override
    public String getId() {
        return id;
    }

    public String getEtiqueta() {
        return etiqueta;
    }

    public void setEtiqueta(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String getTitulo() {
        return titulo;
    }

    public void setTitulo(String titulo) {
        this.titulo = titulo;
    }

    public String getTexto() {
        return texto;
    }

    public void setTexto(String texto) {
        this.texto = texto;
    }

    public String getCta() {
        return cta;
    }

    public void setCta(String cta) {
        this.cta = cta;
    }

    public String getHref() {
        return href;
    }

    public void setHref(String href) {
        this.href = href;
    }

    public String getCta2() {
        return cta2;
    }

    public void setCta2(String cta2) {
        this.cta2 = cta2;
    }

    public String getHref2() {
        return href2;
    }

    public void setHref2(String href2) {
        this.href2 = href2;
    }

    public String getImagem() {
        return imagem;
    }

    public void setImagem(String imagem) {
        this.imagem = imagem == null ? "" : imagem;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public void setAtivo(boolean ativo) {
        this.ativo = ativo;
    }

    public int getOrdem() {
        return ordem;
    }

    public void setOrdem(int ordem) {
        this.ordem = ordem;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Cópia solta (não gerida) com o conteúdo editável — para validar uma alteração sem tocar na entidade gerida. */
    Banner copiaParaValidar() {
        Banner c = new Banner(id, ordem, createdAt);
        c.copiarEditaveisDe(this);
        return c;
    }

    /** Passa para esta entidade os campos que o admin edita (nunca id, ordem nem datas). */
    void copiarEditaveisDe(Banner o) {
        this.etiqueta = o.etiqueta;
        this.titulo = o.titulo;
        this.texto = o.texto;
        this.cta = o.cta;
        this.href = o.href;
        this.cta2 = o.cta2;
        this.href2 = o.href2;
        this.imagem = o.imagem;
        this.ativo = o.ativo;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
