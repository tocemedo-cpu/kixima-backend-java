package ao.kixima.user;

import ao.kixima.common.error.ErrorResponse;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.storage.StorageService;
import ao.kixima.user.dto.UserPublicDto;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Espelha userRoutes.js — a conta do próprio utilizador: perfil, idioma, foto e direitos do titular dos dados. */
@RestController
@RequestMapping("/api/users")
public class UserController {

    /** IDIOMAS de i18n/emails.js. */
    static final List<String> IDIOMAS = List.of("pt", "en", "fr");

    private final UserRepository userRepository;
    private final ProfileService profileService;
    private final DadosPessoaisService dadosPessoaisService;
    private final StorageService storageService;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserRepository userRepository, ProfileService profileService, DadosPessoaisService dadosPessoaisService,
                          StorageService storageService, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.profileService = profileService;
        this.dadosPessoaisService = dadosPessoaisService;
        this.storageService = storageService;
        this.passwordEncoder = passwordEncoder;
    }

    private User eu() {
        return userRepository.findById(CurrentUserHolder.get().id()).orElseThrow(() -> new NotFoundException("Utilizador"));
    }

    @GetMapping("/me")
    @Transactional(readOnly = true)
    public UserPublicDto me() {
        return UserPublicDto.de(eu());
    }

    /** Perfil pessoal — mesmo formato para todas as personas. */
    @GetMapping("/profile")
    public ProfileService.Profile profile() {
        CurrentUser user = CurrentUserHolder.get();
        return profileService.getProfile(user.id(), user.companyId());
    }

    public record LocaleRequest(String locale) {
    }

    public record LocaleDto(String id, String locale) {
    }

    /** Idioma do utilizador — guardado no servidor, que é quem escreve os emails. */
    @PutMapping("/me/locale")
    @Transactional
    public LocaleDto setLocale(@RequestBody(required = false) LocaleRequest body) {
        String pedido = body == null || body.locale() == null ? "" : body.locale().toLowerCase();
        if (!IDIOMAS.contains(pedido)) {
            throw new ValidationException("Idioma inválido. Use um de: " + String.join(", ", IDIOMAS) + ".");
        }
        User u = eu();
        u.setLocale(pedido);
        return new LocaleDto(u.getId(), u.getLocale());
    }

    // --- Direitos do titular dos dados (Lei 22/11) -------------------------

    /** Aceder: tudo o que a plataforma sabe sobre esta conta, num único documento (para descarregar). */
    @GetMapping("/me/dados-pessoais")
    public ResponseEntity<DadosPessoaisService.Documento> dadosPessoais() {
        String id = CurrentUserHolder.get().id();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"kixima-dados-" + id.substring(0, Math.min(8, id.length())) + ".json\"")
                .body(dadosPessoaisService.exportar(id));
    }

    public record AnonimizarRequest(String password, String motivo) {
    }

    /** Eliminar: ANONIMIZAÇÃO, não DELETE — exige a senha atual, é irreversível e fecha a conta. */
    @PostMapping("/me/anonimizar")
    public DadosPessoaisService.ResultadoAnonimizacao anonimizar(@RequestBody(required = false) AnonimizarRequest body) {
        User u = eu();
        String password = body == null || body.password() == null ? "" : body.password();
        if (!passwordEncoder.matches(password, u.getPasswordHash())) {
            throw new ValidationException("Confirme a sua senha atual para eliminar os dados.");
        }
        return dadosPessoaisService.anonimizar(u.getId(), body == null ? null : body.motivo());
    }

    /** Foto de perfil — o front envia a imagem no campo `image`. */
    @PostMapping("/me/avatar")
    @Transactional
    public ResponseEntity<?> setAvatar(@RequestParam(value = "image", required = false) MultipartFile image) {
        if (image == null || image.isEmpty()) {
            return ResponseEntity.badRequest().body(ErrorResponse.of("NO_FILE", "Nenhuma imagem enviada."));
        }
        String tipo = image.getContentType();
        if (tipo == null || !tipo.matches("^image/(png|jpe?g|webp|gif)$")) {
            throw new ValidationException("Imagem inválida — use PNG, JPG, WEBP ou GIF.");
        }
        User u = eu();
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Falha a ler a imagem enviada.", e);
        }
        String originalname = image.getOriginalFilename() == null || image.getOriginalFilename().isBlank() ? "avatar.jpg" : image.getOriginalFilename();
        u.setAvatarUrl(storageService.saveFile(bytes, originalname, tipo, "avatar-" + u.getId(), "avatars"));
        return ResponseEntity.ok(UserPublicDto.de(u));
    }

    @DeleteMapping("/me/avatar")
    @Transactional
    public UserPublicDto removeAvatar() {
        User u = eu();
        u.setAvatarUrl(null);
        return UserPublicDto.de(u);
    }

    /** Só para os testes e para quem quiser inspecionar a lista suportada. */
    static Map<String, Object> idiomas() {
        return Map.of("idiomas", IDIOMAS);
    }
}
