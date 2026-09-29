package ao.kixima.storage;

import ao.kixima.common.error.AppException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O provider S3 do StorageService sem um bucket real: o que se decide antes
 * de tocar na rede (provider ativo, variáveis em falta, URL público) e o que
 * acontece quando o endpoint não responde — um 502 com o motivo, nunca um
 * ficheiro "guardado" que não existe.
 */
class StorageServiceS3Test {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    @TempDir
    Path tmp;

    @Test
    void s3PedidoMasIncompletoCaiParaODiscoLocalEDizOQueFalta() {
        StorageService s = new StorageService("s3", tmp.toString(), "imagens", "AKIA", "", "", "", "", true);
        assertThat(s.providerConfigurado()).isEqualTo("s3");
        assertThat(s.providerAtivo()).isEqualTo("local");
        assertThat(s.emFalta()).containsExactly("STORAGE_SECRET_KEY");
        String url = s.saveFile(PNG, "x.png", "image/png", "teste");
        assertThat(url).startsWith("/api/uploads/teste-");
        assertThat(s.urlAindaVivo(url)).isTrue();
        assertThat(s.urlAindaVivo("/api/uploads/apagado-num-deploy.png")).isFalse();
        assertThat(s.urlAindaVivo("https://cdn.exemplo/x.png")).isTrue();
    }

    @Test
    void urlPublicoSegueAPrecedenciaDoNode() {
        StorageService cdn = new StorageService("s3", tmp.toString(), "imagens", "a", "b", "eu-west-1", "https://x.supabase.co/storage/v1/s3/", "https://cdn.kixima.co.ao/", true);
        assertThat(cdn.publicUrlFor("products/a.png", null)).isEqualTo("https://cdn.kixima.co.ao/products/a.png");
        // Um bucket diferente (o das cópias) nunca sai pelo CDN das imagens.
        assertThat(cdn.publicUrlFor("copias/a.gz", "backups")).isEqualTo("https://x.supabase.co/storage/v1/s3/backups/copias/a.gz");
        StorageService aws = new StorageService("s3", tmp.toString(), "imagens", "a", "b", "eu-west-1", "", "", false);
        assertThat(aws.publicUrlFor("products/a.png", null)).isEqualTo("https://imagens.s3.eu-west-1.amazonaws.com/products/a.png");
    }

    @Test
    void endpointInacessivelDa502ComOMotivoEmVezDeFingir() {
        StorageService s = new StorageService("s3", tmp.toString(), "imagens", "AKIA", "segredo", "us-east-1", "http://127.0.0.1:9", "", true);
        assertThat(s.providerAtivo()).isEqualTo("s3");
        assertThatThrownBy(() -> s.saveFile(PNG, "x.png", "image/png", "teste"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("Não foi possível guardar o ficheiro no armazenamento")
                .hasMessageContaining("inacessível")
                .satisfies(e -> assertThat(((AppException) e).getStatusCode()).isEqualTo(502));
        assertThatThrownBy(() -> s.lerFicheiro("copias/x.gz", "backups"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("Não foi possível ler");
        // Nada foi escrito no disco local a fingir que foi para o bucket.
        assertThat(tmp.toFile().list()).isEmpty();
    }
}
