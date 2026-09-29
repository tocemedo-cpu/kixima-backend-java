package ao.kixima.security;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Espelha backend/src/utils/totp.js — TOTP (RFC 6238) sobre HOTP (RFC 4226),
 * SHA-1, 6 dígitos, período de 30s (compatível com Google/Microsoft
 * Authenticator, Authy). Porte directo, mesmos parâmetros — nunca uma
 * biblioteca TOTP de terceiros com outros valores por omissão, para os
 * segredos já enrolados continuarem a validar identicamente (plano, secção
 * 6, decisão 4).
 */
@Service
public class TotpService {

    private static final String B32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int STEP_SECONDS = 30;
    private static final int DESVIO_MAX_PASSOS = 20; // ±10 minutos
    private final SecureRandom random = new SecureRandom();

    String base32Encode(byte[] buf) {
        int bits = 0;
        int value = 0;
        StringBuilder out = new StringBuilder();
        for (byte b : buf) {
            value = (value << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(B32_ALPHABET.charAt((value >>> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) out.append(B32_ALPHABET.charAt((value << (5 - bits)) & 31));
        return out.toString();
    }

    byte[] base32Decode(String str) {
        String clean = str.toUpperCase(Locale.ROOT).replaceAll("[^A-Z2-7]", "");
        int bits = 0;
        int value = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (char ch : clean.toCharArray()) {
            value = (value << 5) | B32_ALPHABET.indexOf(ch);
            bits += 5;
            if (bits >= 8) {
                out.write((value >>> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    /** Segredo novo: 20 bytes aleatórios (160 bits, recomendação da RFC). */
    public String generateSecret() {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return base32Encode(bytes);
    }

    private String hotp(String secretB32, long counter) {
        byte[] key = base32Decode(secretB32);
        byte[] msg = new byte[8];
        for (int i = 7; i >= 0; i--) {
            msg[i] = (byte) (counter & 0xff);
            counter >>>= 8;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(msg);
            int off = h[h.length - 1] & 0xf;
            int code = ((h[off] & 0x7f) << 24 | (h[off + 1] & 0xff) << 16 | (h[off + 2] & 0xff) << 8 | (h[off + 3] & 0xff)) % 1_000_000;
            return String.format(Locale.ROOT, "%06d", code);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA1 indisponível", e);
        }
    }

    public String totp(String secretB32, long atMillis) {
        return hotp(secretB32, Math.floorDiv(atMillis / 1000, STEP_SECONDS));
    }

    public String totp(String secretB32) {
        return totp(secretB32, System.currentTimeMillis());
    }

    /** Verifica com janela de tolerância ±1 passo, comparação em tempo constante. */
    public boolean verify(String code, String secretB32, long atMillis, int window) {
        String c = code == null ? "" : code.replaceAll("\\D", "");
        if (c.length() != 6 || secretB32 == null) return false;
        long counter = Math.floorDiv(atMillis / 1000, STEP_SECONDS);
        for (int i = -window; i <= window; i++) {
            String expected = hotp(secretB32, counter + i);
            if (constantTimeEquals(expected, c)) return true;
        }
        return false;
    }

    public boolean verify(String code, String secretB32) {
        return verify(code, secretB32, System.currentTimeMillis(), 1);
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    /**
     * Se o código não foi aceite, procura-o numa janela larga para descobrir
     * se o problema é o relógio. Devolve o desvio em segundos (positivo =
     * adiantado) ou null se não bater em lado nenhum. NUNCA usado para
     * aceitar o código — só para explicar a falha (ver explicarFalha).
     */
    public Integer desvioDeRelogio(String code, String secretB32, long atMillis, int window) {
        String c = code == null ? "" : code.replaceAll("\\D", "");
        if (c.length() != 6 || secretB32 == null) return null;
        long counter = Math.floorDiv(atMillis / 1000, STEP_SECONDS);
        for (int i = -DESVIO_MAX_PASSOS; i <= DESVIO_MAX_PASSOS; i++) {
            if (Math.abs(i) <= window) continue;
            if (hotp(secretB32, counter + i).equals(c)) return i * STEP_SECONDS;
        }
        return null;
    }

    public String explicarFalha(String code, String secretB32, long atMillis, int window) {
        Integer desvio = desvioDeRelogio(code, secretB32, atMillis, window);
        if (desvio == null) {
            return "Código incorreto. Confirme que está a ler a entrada KIXIMA correta na app — se leu o "
                    + "código QR mais do que uma vez, ficaram várias entradas com o mesmo nome e só a última serve.";
        }
        int segundos = Math.abs(desvio);
        String sentido = desvio > 0 ? "adiantado" : "atrasado";
        return "O código corresponde, mas o relógio do seu telemóvel está cerca de " + segundos + " segundos "
                + sentido + ". Ative a hora automática no telemóvel (ou, no Google Authenticator: Definições → "
                + "Correção horária dos códigos → Sincronizar agora) e tente outra vez.";
    }

    public String explicarFalha(String code, String secretB32) {
        return explicarFalha(code, secretB32, System.currentTimeMillis(), 1);
    }

    public String otpauthUrl(String secret, String label) {
        String issuer = "KIXIMA";
        String params = "secret=" + secret
                + "&issuer=" + urlEnc(issuer)
                + "&algorithm=SHA1&digits=6&period=" + STEP_SECONDS;
        return "otpauth://totp/" + urlEnc(issuer) + ":" + urlEnc(label) + "?" + params;
    }

    private String urlEnc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
