package ao.kixima.banner;

import ao.kixima.banner.dto.PublicBannerDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Banners da homepage pública — só leitura, sem sessão ({@link ao.kixima.security.PublicPaths}). Só os activos que já
 * têm imagem, por ordem, sem os campos de gestão.
 */
@RestController
@RequestMapping("/api/public/banners")
public class BannerPublicController {

    private final BannerService bannerService;

    public BannerPublicController(BannerService bannerService) {
        this.bannerService = bannerService;
    }

    @GetMapping
    public List<PublicBannerDto> listar() {
        return bannerService.publicos();
    }
}
