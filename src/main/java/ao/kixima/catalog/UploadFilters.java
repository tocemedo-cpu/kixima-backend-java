package ao.kixima.catalog;

import ao.kixima.common.error.AppException;
import ao.kixima.common.error.ValidationException;
import org.springframework.web.multipart.MultipartFile;

import java.util.regex.Pattern;

/**
 * Espelha backend/src/config/upload.js — os filtros do multer, por tipo de
 * campo: só imagens web na galeria/capa, PDF ou imagem nos documentos,
 * .xlsx no carregamento em massa. Um ficheiro maior do que o limite dá 413
 * com a mesma frase do errorHandler.js (LIMIT_FILE_SIZE).
 */
public final class UploadFilters {

    public static final long MB = 1024L * 1024;
    /** `upload` — imagem de capa (12MB). */
    public static final long LIMITE_IMAGEM = 12 * MB;
    /** `uploadProductMedia` — galeria e documentos técnicos (15MB). */
    public static final long LIMITE_MEDIA = 15 * MB;
    /** `uploadSpreadsheet` — folha de cálculo (25MB). */
    public static final long LIMITE_FOLHA = 25 * MB;
    /** `uploadDocuments` — comprovativos e documentos de credenciamento (10MB). */
    public static final long LIMITE_DOCUMENTO = 10 * MB;

    private static final Pattern IMAGEM = Pattern.compile("^image/(png|jpe?g|webp|gif)$");
    private static final Pattern HEIC = Pattern.compile("heic|heif", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEIC_NOME = Pattern.compile("\\.hei[cf]$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOLHA_TIPO = Pattern.compile("(sheet|excel|officedocument\\.spreadsheetml)");
    private static final Pattern FOLHA_NOME = Pattern.compile("\\.xlsx?$", Pattern.CASE_INSENSITIVE);

    private UploadFilters() {
    }

    private static String tipo(MultipartFile f) {
        return f.getContentType() == null ? "" : f.getContentType();
    }

    private static String nome(MultipartFile f) {
        return f.getOriginalFilename() == null ? "" : f.getOriginalFilename();
    }

    private static ValidationException formatoRecusado(MultipartFile f) {
        boolean isHeic = HEIC.matcher(tipo(f)).find() || HEIC_NOME.matcher(nome(f)).find();
        return new ValidationException(isHeic
                ? "Formato HEIC/HEIF (foto de iPhone) não é suportado pelos navegadores. Converta a imagem para JPG ou PNG antes de carregar."
                : "Formato de imagem não suportado. Use PNG, JPG, WEBP ou GIF.");
    }

    /**
     * O que o multer faz ao passar `limits.fileSize`: MulterError com
     * {@code code: 'LIMIT_FILE_SIZE'}, que o errorHandler.js traduz em 413 com
     * esta frase — o {@code code} viaja tal e qual no envelope.
     */
    public static void tamanho(MultipartFile f, long limite) {
        if (f.getSize() > limite) {
            throw new AppException("O ficheiro é demasiado grande. Reduza o tamanho da imagem e tente novamente.", 413, "LIMIT_FILE_SIZE");
        }
    }

    public static boolean eImagem(MultipartFile f) {
        return IMAGEM.matcher(tipo(f)).matches();
    }

    /** Campos `mainImage`/`gallery`/`image` — só imagem. */
    public static MultipartFile imagem(MultipartFile f, long limite) {
        tamanho(f, limite);
        if (!eImagem(f)) throw formatoRecusado(f);
        return f;
    }

    /** Documentos técnicos — PDF ou imagem. */
    public static MultipartFile documento(MultipartFile f, long limite) {
        tamanho(f, limite);
        if (!eImagem(f) && !"application/pdf".equals(tipo(f))) {
            throw new ValidationException("Documento inválido — use PDF ou imagem (PNG/JPG).");
        }
        return f;
    }

    /** Carregamento em massa — .xlsx pelo tipo declarado ou pela extensão. */
    public static MultipartFile folha(MultipartFile f) {
        tamanho(f, LIMITE_FOLHA);
        if (!FOLHA_TIPO.matcher(tipo(f)).find() && !FOLHA_NOME.matcher(nome(f)).find()) {
            throw new ValidationException("Ficheiro inválido — envie uma folha de cálculo .xlsx.");
        }
        return f;
    }
}
