package ao.kixima.banner;

import ao.kixima.audit.AuditService;
import ao.kixima.banner.dto.BannerDto;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.RequirePermission;
import ao.kixima.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ao.kixima.security.AdminArea.OPERACOES;
import static ao.kixima.security.PersonaRole.ADMIN_SISTEMA;

/**
 * Gestão dos banners da homepage pública pelo Admin do Sistema (área «Operação da Plataforma»; um Super Admin, com
 * {@code adminAreas} vazio, passa sempre). Contrato: {@code docs/api-banners.md} do frontend.
 *
 * <p>{@code PUT /ordem} é um caminho literal e tem prioridade sobre {@code PUT /{id}} no Spring (o literal é mais
 * específico), por isso um banner nunca é confundido com a palavra «ordem». Cada alteração fica no trilho de auditoria.
 */
@RestController
@RequestMapping("/api/admin/banners")
public class BannerAdminController {

    private final BannerService bannerService;
    private final AuditService auditService;

    public BannerAdminController(BannerService bannerService, AuditService auditService) {
        this.bannerService = bannerService;
        this.auditService = auditService;
    }

    @GetMapping
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(OPERACOES)
    public List<BannerDto> listar() {
        return bannerService.listar();
    }

    @PostMapping
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(OPERACOES)
    public BannerDto criar(@RequestBody(required = false) Map<String, Object> corpo, HttpServletRequest req) {
        BannerDto criado = bannerService.criar(corpo);
        auditar(req, "BANNER_CRIADO", criado, Map.of("ativo", criado.ativo()));
        return criado;
    }

    @PutMapping("/ordem")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(OPERACOES)
    public List<BannerDto> reordenar(@RequestBody(required = false) Map<String, Object> corpo, HttpServletRequest req) {
        List<BannerDto> ordenados = bannerService.reordenar(corpo == null ? null : corpo.get("ids"));
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(CurrentUserHolder.get(), req), "BANNER_REORDENADO",
                "Banner", "ordem", null, Map.of("ids", ordenados.stream().map(BannerDto::id).toList())));
        return ordenados;
    }

    @PutMapping("/{id}")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(OPERACOES)
    public BannerDto atualizar(@PathVariable String id, @RequestBody(required = false) Map<String, Object> corpo, HttpServletRequest req) {
        BannerDto atualizado = bannerService.atualizar(id, corpo);
        // Publicar/despublicar é o que muda o que o público vê — fica com acção própria.
        String accao = corpo != null && corpo.containsKey("ativo") ? (atualizado.ativo() ? "BANNER_PUBLICADO" : "BANNER_DESPUBLICADO") : "BANNER_ATUALIZADO";
        auditar(req, accao, atualizado, Map.of("campos", corpo == null ? List.of() : List.copyOf(corpo.keySet())));
        return atualizado;
    }

    @PostMapping("/{id}/imagem")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(OPERACOES)
    public BannerDto carregarImagem(@PathVariable String id, @RequestParam(value = "image", required = false) MultipartFile image,
                                    HttpServletRequest req) {
        BannerDto atualizado = bannerService.carregarImagem(id, image);
        auditar(req, "BANNER_IMAGEM_ATUALIZADA", atualizado, Map.of("imagem", atualizado.imagem()));
        return atualizado;
    }

    @DeleteMapping("/{id}")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(OPERACOES)
    public Map<String, Boolean> remover(@PathVariable String id, HttpServletRequest req) {
        BannerDto removido = bannerService.remover(id);
        auditar(req, "BANNER_REMOVIDO", removido, new LinkedHashMap<>());
        return Map.of("ok", true);
    }

    private void auditar(HttpServletRequest req, String accao, BannerDto banner, Map<String, ?> detalhes) {
        auditService.recordSafe(new AuditService.Entry(auditService.actorFrom(CurrentUserHolder.get(), req), accao,
                "Banner", banner.id(), banner.titulo(), detalhes));
    }
}
