package ao.kixima.storage;

import ao.kixima.common.error.AppException;
import ao.kixima.common.error.UnauthorizedException;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Espelha backend/src/routes/uploadsRoutes.js — serve os ficheiros
 * carregados em modo de armazenamento 'local'. Antes (achado de
 * arquitetura documentado no Node), {@code express.static} servia esta
 * pasta inteira a qualquer pedido, sem sessão nem verificação de posse;
 * aqui, tal como lá, cada pedido passa por {@link UploadAccessService}.
 *
 * `/api/uploads/*` está listado em {@link ao.kixima.security.PublicPaths}
 * — não porque o ficheiro seja sempre público, mas porque
 * {@link ao.kixima.security.AuthenticationFilter} trata "público" como
 * "autenticação OPCIONAL": um token válido continua a popular
 * {@link CurrentUserHolder} normalmente, só o token AUSENTE/inválido segue
 * sem utilizador — exactamente o {@code optionalAuthenticate} do Node.
 */
@RestController
public class UploadsController {

    private static final Pattern NOME_VALIDO = Pattern.compile("^[a-z0-9-]+\\.[a-z0-9]+$", Pattern.CASE_INSENSITIVE);

    private final UploadAccessService uploadAccessService;
    private final StorageService storageService;

    public UploadsController(UploadAccessService uploadAccessService, StorageService storageService) {
        this.uploadAccessService = uploadAccessService;
        this.storageService = storageService;
    }

    private static AppException ficheiroNaoEncontrado() {
        return new AppException(
                "Este ficheiro já não está disponível. Peça para o documento ser enviado novamente.", 404, "FILE_NOT_FOUND");
    }

    @GetMapping("/api/uploads/{filename}")
    public ResponseEntity<byte[]> servir(@PathVariable String filename) {
        if (!NOME_VALIDO.matcher(filename).matches()) throw ficheiroNaoEncontrado();

        CurrentUser user = CurrentUserHolder.get();
        UploadAccessService.Acesso acesso = uploadAccessService.resolverAcesso(filename, user);
        if (!acesso.publico() && !acesso.permitido()) {
            if (user == null) throw new UnauthorizedException("Inicie sessão para aceder a este ficheiro.");
            // 404 e não 403: não se confirma a quem não tem posse que o ficheiro existe.
            throw ficheiroNaoEncontrado();
        }

        byte[] buffer;
        try {
            buffer = storageService.readFile(filename);
        } catch (IOException e) {
            throw ficheiroNaoEncontrado();
        }

        FileSignature.Assinatura detetado = FileSignature.detetar(buffer);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(detetado != null
                ? MediaType.parseMediaType(detetado.tipo())
                : MediaType.APPLICATION_OCTET_STREAM);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"");
        headers.set(HttpHeaders.CACHE_CONTROL, acesso.publico() ? "public, max-age=31536000, immutable" : "private, no-store");
        return new ResponseEntity<>(buffer, headers, org.springframework.http.HttpStatus.OK);
    }
}
