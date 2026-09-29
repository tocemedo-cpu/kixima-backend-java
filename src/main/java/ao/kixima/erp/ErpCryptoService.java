package ao.kixima.erp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * Espelha backend/src/services/erpCrypto.js — AES-256-GCM sobre as
 * credenciais ERP guardadas em {@code CompanyErpConfig.configEnc}. MESMO
 * formato do kixima-integration-service:
 * {@code base64( iv(12) | authTag(16) | ciphertext )}. A chave vem de
 * {@code ERP_CONFIG_ENCRYPTION_KEY} (32 bytes em hex) — só é exigida
 * quando se cifra/decifra, o arranque do Java não depende dela (mesmo
 * princípio do Node).
 */
@Service
public class ErpCryptoService {

    private static final int IV_LEN = 12;
    private static final int TAG_LEN_BITS = 128;

    private final String hexKey;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public ErpCryptoService(@Value("${kixima.erp.config-encryption-key:}") String hexKey, ObjectMapper objectMapper) {
        this.hexKey = hexKey;
        this.objectMapper = objectMapper;
    }

    private SecretKeySpec chave() {
        if (hexKey == null || !hexKey.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException(
                    "ERP_CONFIG_ENCRYPTION_KEY inválida: 32 bytes em hex (64 caracteres). "
                            + "Gere com: openssl rand -hex 32");
        }
        byte[] bytes = new byte[32];
        for (int i = 0; i < 32; i++) {
            bytes[i] = (byte) Integer.parseInt(hexKey.substring(i * 2, i * 2 + 2), 16);
        }
        return new SecretKeySpec(bytes, "AES");
    }

    public String encryptJson(Map<String, Object> obj) {
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, chave(), new GCMParameterSpec(TAG_LEN_BITS, iv));
            byte[] plaintext = objectMapper.writeValueAsString(obj).getBytes(StandardCharsets.UTF_8);
            // Java's GCM output já inclui a authTag (16 bytes) no fim do ciphertext — iv | (ciphertext|tag).
            byte[] ciphertextComTag = cipher.doFinal(plaintext);
            byte[] ct = new byte[ciphertextComTag.length - 16];
            byte[] tag = new byte[16];
            System.arraycopy(ciphertextComTag, 0, ct, 0, ct.length);
            System.arraycopy(ciphertextComTag, ct.length, tag, 0, 16);

            byte[] out = new byte[IV_LEN + 16 + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(tag, 0, out, IV_LEN, 16);
            System.arraycopy(ct, 0, out, IV_LEN + 16, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Falha a cifrar configuração ERP.", e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> decryptJson(String payload) {
        try {
            byte[] buf = Base64.getDecoder().decode(payload);
            byte[] iv = new byte[IV_LEN];
            byte[] tag = new byte[16];
            byte[] ct = new byte[buf.length - IV_LEN - 16];
            System.arraycopy(buf, 0, iv, 0, IV_LEN);
            System.arraycopy(buf, IV_LEN, tag, 0, 16);
            System.arraycopy(buf, IV_LEN + 16, ct, 0, ct.length);

            // Java's GCM espera ciphertext|tag concatenados no doFinal.
            byte[] ciphertextComTag = new byte[ct.length + 16];
            System.arraycopy(ct, 0, ciphertextComTag, 0, ct.length);
            System.arraycopy(tag, 0, ciphertextComTag, ct.length, 16);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, chave(), new GCMParameterSpec(TAG_LEN_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertextComTag);
            return objectMapper.readValue(plaintext, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("Falha a decifrar configuração ERP.", e);
        }
    }
}
