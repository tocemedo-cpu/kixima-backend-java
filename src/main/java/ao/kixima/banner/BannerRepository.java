package ao.kixima.banner;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BannerRepository extends JpaRepository<Banner, String> {

    List<Banner> findAllByOrderByOrdemAscCreatedAtAsc();

    /** Montra pública: só activos e já com imagem. */
    List<Banner> findByAtivoTrueAndImagemNotOrderByOrdemAscCreatedAtAsc(String imagemVazia);

    /** A imagem de um banner é pública (homepage): {@code UploadAccessService} usa isto para servir o ficheiro sem sessão. */
    boolean existsByImagem(String imagem);
}
