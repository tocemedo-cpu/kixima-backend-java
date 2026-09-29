package ao.kixima.storage;

import ao.kixima.common.error.ValidationException;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Espelha backend/src/utils/fileSignature.js — confere que um ficheiro é
 * mesmo do tipo que diz ser, olhando para os primeiros bytes (a única
 * parte do ficheiro que o formato obriga a estar certa). O
 * {@code Content-Type} declarado vem de quem envia — é uma afirmação, não
 * uma verificação; um executável renomeado para .pdf passava por todos os
 * filtros de extensão/mimetype declarado.
 */
public final class FileSignature {

    public record Assinatura(String tipo, String rotulo, List<Parte> partes) {
    }

    public record Parte(int em, int[] bytes) {
    }

    private static final List<Assinatura> ASSINATURAS = List.of(
            new Assinatura("application/pdf", "PDF", List.of(new Parte(0, new int[]{0x25, 0x50, 0x44, 0x46}))),
            new Assinatura("image/png", "PNG", List.of(new Parte(0, new int[]{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}))),
            new Assinatura("image/jpeg", "JPEG", List.of(new Parte(0, new int[]{0xff, 0xd8, 0xff}))),
            new Assinatura("image/gif", "GIF", List.of(new Parte(0, new int[]{0x47, 0x49, 0x46, 0x38}))),
            new Assinatura("image/webp", "WEBP", List.of(
                    new Parte(0, new int[]{0x52, 0x49, 0x46, 0x46}),
                    new Parte(8, new int[]{0x57, 0x45, 0x42, 0x50}))),
            new Assinatura("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "XLSX",
                    List.of(new Parte(0, new int[]{0x50, 0x4b, 0x03, 0x04}))));

    private static final Map<String, String> EQUIVALENTES = Map.of("image/jpg", "image/jpeg", "image/pjpeg", "image/jpeg");

    private FileSignature() {
    }

    private static String normalizar(String mimetype) {
        String m = mimetype == null ? "" : mimetype.toLowerCase(Locale.ROOT).split(";")[0].trim();
        return EQUIVALENTES.getOrDefault(m, m);
    }

    private static boolean bate(byte[] buffer, Assinatura assinatura) {
        for (Parte parte : assinatura.partes()) {
            if (buffer.length < parte.em() + parte.bytes().length) return false;
            for (int i = 0; i < parte.bytes().length; i++) {
                if ((buffer[parte.em() + i] & 0xFF) != parte.bytes()[i]) return false;
            }
        }
        return true;
    }

    /** Que formato é este ficheiro, olhando só para o conteúdo? {@code null} se nenhum conhecido. */
    public static Assinatura detetar(byte[] buffer) {
        if (buffer == null || buffer.length == 0) return null;
        for (Assinatura a : ASSINATURAS) {
            if (bate(buffer, a)) return a;
        }
        return null;
    }

    /**
     * Lança se o conteúdo não corresponder ao tipo declarado. Um tipo que
     * não se sabe verificar (fora de {@link #ASSINATURAS}) não é recusado
     * aqui — os filtros do controller já restringem o que entra.
     */
    public static void verificar(byte[] buffer, String mimetypeDeclarado, String originalname) {
        String declarado = normalizar(mimetypeDeclarado);
        Assinatura esperada = ASSINATURAS.stream().filter(a -> a.tipo().equals(declarado)).findFirst().orElse(null);
        if (esperada == null) return;

        Assinatura real = detetar(buffer);
        String nome = originalname == null ? "ficheiro" : originalname;
        if (real != null && real.tipo().equals(declarado)) return;

        throw new ValidationException(real != null
                ? "O ficheiro \"" + nome + "\" foi enviado como " + esperada.rotulo() + " mas o conteúdo é " + real.rotulo()
                + ". Converta-o para o formato certo ou envie o ficheiro original."
                : "O ficheiro \"" + nome + "\" diz ser " + esperada.rotulo() + " mas o conteúdo não é de nenhum formato "
                + "reconhecido. Pode estar corrompido ou ter sido apenas renomeado — envie o ficheiro original.");
    }
}
