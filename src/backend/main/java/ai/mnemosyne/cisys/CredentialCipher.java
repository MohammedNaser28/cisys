/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Encrypts team Git tokens with AES-256-GCM. The stored value is {@code [version][nonce]
 * [ciphertext and tag]}, so the format can gain a new version later. The key is read from {@code
 * cisys.secret-key} (the {@code CISYS_SECRET_KEY} environment variable), base64 for 32 bytes. It is
 * parsed only when a token is encrypted or decrypted, so a missing or invalid key never prevents
 * the application from starting.
 *
 * <p>Exception messages never contain the token or the key.
 */
@ApplicationScoped
public class CredentialCipher {
  /** The only format version this build writes and reads. */
  static final byte VERSION = 1;

  static final int KEY_BYTES = 32;
  static final int NONCE_BYTES = 12;
  static final int TAG_BITS = 128;

  private static final String NOT_CONFIGURED = "Team credential encryption is not configured";
  private static final String INVALID_KEY = "Team credential encryption is misconfigured";
  private static final String CANNOT_DECRYPT = "The stored team credential cannot be decrypted";

  private final String configuredKey;
  private final SecureRandom random = new SecureRandom();

  @Inject
  public CredentialCipher(
      @ConfigProperty(name = "cisys.secret-key") Optional<String> configuredKey) {
    this(configuredKey.orElse(""));
  }

  /** For tests and callers that already hold the key. */
  CredentialCipher(String configuredKey) {
    this.configuredKey = configuredKey;
  }

  /** Fails fast when the key is missing or invalid, before any token is accepted. */
  public void checkKey() {
    key();
  }

  /** Encrypts a token bound to a team; the result is safe to store as {@code bytea}. */
  public byte[] encrypt(String token, Long teamId) {
    if (token == null) {
      throw new IllegalArgumentException("token is required");
    }
    if (teamId == null) {
      throw new IllegalArgumentException("team id is required");
    }
    try {
      byte[] nonce = new byte[NONCE_BYTES];
      random.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(teamId.toString().getBytes(StandardCharsets.UTF_8));
      byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
      byte[] stored = new byte[1 + NONCE_BYTES + encrypted.length];
      stored[0] = VERSION;
      System.arraycopy(nonce, 0, stored, 1, NONCE_BYTES);
      System.arraycopy(encrypted, 0, stored, 1 + NONCE_BYTES, encrypted.length);
      return stored;
    } catch (GeneralSecurityException e) {
      // The message is fixed: a JCE provider error must not expose the plaintext.
      throw new CredentialException(INVALID_KEY);
    }
  }

  /** Decrypts a team-bound token; a tampered value, team or key is rejected. */
  public String decrypt(byte[] stored, Long teamId) {
    if (stored == null || stored.length < 1 + NONCE_BYTES + TAG_BITS / 8) {
      throw new CredentialException(CANNOT_DECRYPT);
    }
    if (stored[0] != VERSION) {
      throw new CredentialException(CANNOT_DECRYPT);
    }
    if (teamId == null) {
      throw new CredentialException(CANNOT_DECRYPT);
    }
    try {
      byte[] nonce = new byte[NONCE_BYTES];
      System.arraycopy(stored, 1, nonce, 0, NONCE_BYTES);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(teamId.toString().getBytes(StandardCharsets.UTF_8));
      byte[] plaintext = cipher.doFinal(stored, 1 + NONCE_BYTES, stored.length - 1 - NONCE_BYTES);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      // Do not attach the cause: it is not needed and could carry provider detail.
      throw new CredentialException(CANNOT_DECRYPT);
    }
  }

  private SecretKeySpec key() {
    String value = configuredKey == null ? "" : configuredKey.trim();
    if (value.isEmpty()) {
      throw new CredentialException(NOT_CONFIGURED);
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException e) {
      throw new CredentialException(INVALID_KEY);
    }
    if (bytes.length != KEY_BYTES) {
      throw new CredentialException(INVALID_KEY);
    }
    return new SecretKeySpec(bytes, "AES");
  }

  /** A key or decryption problem; the message is safe to show to callers and log. */
  public static class CredentialException extends RuntimeException {
    CredentialException(String message) {
      super(message);
    }
  }
}
