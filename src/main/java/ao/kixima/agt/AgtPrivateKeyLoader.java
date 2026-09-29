package ao.kixima.agt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Espelha o essencial de {@code lerChavePrivadaAgt()}/
 * {@code diagnosticoChavePrivadaAgt()} em backend/src/config/env.js: chave
 * primeiro de {@code AGT_JWS_PRIVATE_KEY_BASE64} (painel de variáveis de
 * ambiente em produção), com fallback para um ficheiro local de
 * desenvolvimento (nunca comitado). NÃO PORTADO (fora de âmbito para o
 * backend Java, específico do hosting do Node): a lista de caminhos
 * "Secret File" do Render (CAMINHOS_SECRET_FILE_RENDER) — infra-estrutura
 * do Node, não parte da integração AGT em si.
 *
 * Aceita PEM em PKCS#8 ("-----BEGIN PRIVATE KEY-----", o que o
 * {@code openssl genpkey}/Java produzem) e em PKCS#1
 * ("-----BEGIN RSA PRIVATE KEY-----", o formato mais comum de chaves RSA
 * geradas por {@code openssl genrsa}/muitas autoridades) — envolve o DER
 * PKCS#1 no cabeçalho PKCS#8 fixo antes de o entregar ao
 * {@link KeyFactory}, já que a API padrão do Java só lê PKCS#8
 * directamente.
 */
final class AgtPrivateKeyLoader {

    private AgtPrivateKeyLoader() {
    }

    record Resultado(PrivateKey chave, String fonte) {
    }

    static Resultado carregar(String base64Env, String caminhoFicheiroLocal) {
        String base64 = base64Env == null ? "" : base64Env.trim();
        if (!base64.isEmpty()) {
            String pem = new String(Base64.getDecoder().decode(base64), java.nio.charset.StandardCharsets.UTF_8);
            PrivateKey chave = tentarInterpretar(pem);
            if (chave != null) return new Resultado(chave, "variável de ambiente (AGT_JWS_PRIVATE_KEY_BASE64)");
        }
        if (caminhoFicheiroLocal != null && !caminhoFicheiroLocal.isBlank()) {
            Path caminho = Path.of(caminhoFicheiroLocal);
            if (Files.isReadable(caminho)) {
                try {
                    String conteudo = Files.readString(caminho, java.nio.charset.StandardCharsets.UTF_8);
                    PrivateKey chave = tentarInterpretar(conteudo);
                    if (chave == null) {
                        // O ficheiro local também pode guardar o valor já em Base64 (não o PEM em texto).
                        try {
                            chave = tentarInterpretar(new String(Base64.getDecoder().decode(conteudo.trim()),
                                    java.nio.charset.StandardCharsets.UTF_8));
                        } catch (IllegalArgumentException ignorado) {
                            // não era Base64 válido — já tentámos como PEM directo acima, fica por resolver.
                        }
                    }
                    if (chave != null) return new Resultado(chave, "ficheiro local (" + caminhoFicheiroLocal + ")");
                } catch (Exception ignorado) {
                    // ficheiro ilegível/corrompido — cai para "sem chave", mesmo princípio do Node.
                }
            }
        }
        return new Resultado(null, null);
    }

    private static PrivateKey tentarInterpretar(String pemOuVazio) {
        if (pemOuVazio == null) return null;
        try {
            return parsePem(pemOuVazio);
        } catch (Exception e) {
            return null;
        }
    }

    private static PrivateKey parsePem(String pem) throws Exception {
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        String limpo = pem
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        if (limpo.isEmpty()) throw new IllegalArgumentException("PEM vazio.");
        byte[] der = Base64.getDecoder().decode(limpo);
        byte[] pkcs8Der = pkcs1 ? envolverPkcs1EmPkcs8(der) : der;
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8Der));
    }

    /**
     * Envolve um {@code RSAPrivateKey} PKCS#1 (ASN.1 bruto) no invólucro
     * PKCS#8 mínimo — {@code PrivateKeyInfo { version=0, algorithm=rsaEncryption, privateKey=OCTET STRING(pkcs1Der) }}.
     * Prefixo fixo e bem conhecido para RSA (OID 1.2.840.113549.1.1.1);
     * evita puxar uma dependência (ex. BouncyCastle) só para isto.
     */
    private static byte[] envolverPkcs1EmPkcs8(byte[] pkcs1Der) {
        // INTEGER version=0 + SEQUENCE AlgorithmIdentifier{OID rsaEncryption, NULL} —
        // prefixo fixo, sempre os mesmos bytes para qualquer chave RSA.
        byte[] octetLen = comprimentoAsn1(pkcs1Der.length);
        byte[] semTamanho = concat(
                new byte[]{0x02, 0x01, 0x00, 0x30, 0x0D, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00},
                new byte[]{0x04}, octetLen, pkcs1Der);
        byte[] seqLen = comprimentoAsn1(semTamanho.length);
        return concat(new byte[]{0x30}, seqLen, semTamanho);
    }

    private static byte[] comprimentoAsn1(int tamanho) {
        if (tamanho < 0x80) return new byte[]{(byte) tamanho};
        if (tamanho <= 0xFF) return new byte[]{(byte) 0x81, (byte) tamanho};
        return new byte[]{(byte) 0x82, (byte) (tamanho >> 8), (byte) tamanho};
    }

    private static byte[] concat(byte[]... partes) {
        int total = 0;
        for (byte[] p : partes) total += p.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : partes) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }
}
