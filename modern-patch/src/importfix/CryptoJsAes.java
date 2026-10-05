package importfix;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Reads and writes text the way the game's CryptoJS calls do: AES.encrypt(text, passphrase)
 * and AES.decrypt(text, passphrase). That is OpenSSL's salted format in Base64:
 * "Salted__", an 8-byte salt, then AES-256-CBC with the key and IV derived from the
 * passphrase and salt by one round of MD5 per block (EVP_BytesToKey).
 *
 * The online game stores its run history in the browser this way, so syncing run
 * history has to decode and encode it.
 */
final class CryptoJsAes {
    private static final byte[] MAGIC = "Salted__".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT_BYTES = 8;
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 16;

    private CryptoJsAes() {
    }

    static String decrypt(String base64, String passphrase) throws GeneralSecurityException {
        byte[] data;
        try {
            data = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("not Base64", e);
        }
        int header = MAGIC.length + SALT_BYTES;
        if (data.length <= header || !Arrays.equals(Arrays.copyOf(data, MAGIC.length), MAGIC)) {
            throw new GeneralSecurityException("not in the salted format");
        }
        byte[] salt = Arrays.copyOfRange(data, MAGIC.length, header);
        Cipher cipher = cipher(Cipher.DECRYPT_MODE, passphrase, salt);
        return new String(cipher.doFinal(data, header, data.length - header), StandardCharsets.UTF_8);
    }

    static String encrypt(String text, String passphrase) throws GeneralSecurityException {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, passphrase, salt)
                .doFinal(text.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[MAGIC.length + SALT_BYTES + encrypted.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        System.arraycopy(salt, 0, out, MAGIC.length, SALT_BYTES);
        System.arraycopy(encrypted, 0, out, MAGIC.length + SALT_BYTES, encrypted.length);
        return Base64.getEncoder().encodeToString(out);
    }

    private static Cipher cipher(int mode, String passphrase, byte[] salt) throws GeneralSecurityException {
        byte[] password = passphrase.getBytes(StandardCharsets.UTF_8);
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        byte[] derived = new byte[KEY_BYTES + IV_BYTES];
        byte[] block = new byte[0];
        for (int filled = 0; filled < derived.length; filled += block.length) {
            md5.update(block);
            md5.update(password);
            md5.update(salt);
            block = md5.digest();
            System.arraycopy(block, 0, derived, filled, Math.min(block.length, derived.length - filled));
        }
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(mode, new SecretKeySpec(derived, 0, KEY_BYTES, "AES"),
                new IvParameterSpec(derived, KEY_BYTES, IV_BYTES));
        return cipher;
    }
}
