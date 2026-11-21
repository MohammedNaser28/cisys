/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class CredentialCipherTest {
  private static final String TOKEN = "ghp_a-very-secret-token";
  private static final Long TEAM_ID = 42L;

  private static String key(int fill) {
    byte[] bytes = new byte[CredentialCipher.KEY_BYTES];
    Arrays.fill(bytes, (byte) fill);
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static CredentialCipher cipher(String key) {
    return new CredentialCipher(key);
  }

  @Test
  void roundTripsAToken() {
    CredentialCipher cipher = cipher(key(1));
    byte[] stored = cipher.encrypt(TOKEN, TEAM_ID);
    assertEquals(TOKEN, cipher.decrypt(stored, TEAM_ID));
  }

  @Test
  void writesTheFormatVersionAndAFreshNonce() {
    CredentialCipher cipher = cipher(key(2));
    byte[] first = cipher.encrypt(TOKEN, TEAM_ID);
    byte[] second = cipher.encrypt(TOKEN, TEAM_ID);
    assertEquals(CredentialCipher.VERSION, first[0]);
    assertFalse(Arrays.equals(first, second), "two encryptions must not be identical");
    assertFalse(
        Arrays.equals(
            Arrays.copyOfRange(first, 1, 1 + CredentialCipher.NONCE_BYTES),
            Arrays.copyOfRange(second, 1, 1 + CredentialCipher.NONCE_BYTES)),
        "every encryption must use a fresh nonce");
    assertEquals(TOKEN, cipher.decrypt(first, TEAM_ID));
    assertEquals(TOKEN, cipher.decrypt(second, TEAM_ID));
  }

  @Test
  void rejectsATamperedCiphertext() {
    CredentialCipher cipher = cipher(key(3));
    byte[] stored = cipher.encrypt(TOKEN, TEAM_ID);
    stored[stored.length - 1] ^= 1;
    CredentialCipher.CredentialException failure =
        assertThrows(
            CredentialCipher.CredentialException.class, () -> cipher.decrypt(stored, TEAM_ID));
    assertFalse(failure.getMessage().contains(TOKEN));
  }

  @Test
  void rejectsADifferentKey() {
    byte[] stored = cipher(key(4)).encrypt(TOKEN, TEAM_ID);
    CredentialCipher other = cipher(key(5));
    assertThrows(CredentialCipher.CredentialException.class, () -> other.decrypt(stored, TEAM_ID));
  }

  @Test
  void rejectsADifferentTeamId() {
    byte[] stored = cipher(key(4)).encrypt(TOKEN, TEAM_ID);
    assertThrows(
        CredentialCipher.CredentialException.class, () -> cipher(key(4)).decrypt(stored, 43L));
  }

  @Test
  void rejectsAMissingKeyWithoutLeakingTheToken() {
    CredentialCipher cipher = cipher("");
    CredentialCipher.CredentialException failure =
        assertThrows(
            CredentialCipher.CredentialException.class, () -> cipher.encrypt(TOKEN, TEAM_ID));
    assertTrue(failure.getMessage().contains("not configured"));
    assertFalse(failure.getMessage().contains(TOKEN));
  }

  @Test
  void rejectsAnInvalidKey() {
    assertThrows(
        CredentialCipher.CredentialException.class,
        () -> cipher("not-base64").encrypt(TOKEN, TEAM_ID));
    String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
    assertThrows(
        CredentialCipher.CredentialException.class, () -> cipher(shortKey).encrypt(TOKEN, TEAM_ID));
  }

  @Test
  void rejectsAnUnknownFormatVersion() {
    CredentialCipher cipher = cipher(key(6));
    byte[] stored = cipher.encrypt(TOKEN, TEAM_ID);
    stored[0] = 99;
    assertThrows(CredentialCipher.CredentialException.class, () -> cipher.decrypt(stored, TEAM_ID));
  }

  @Test
  void rejectsTruncatedOrMissingCiphertext() {
    CredentialCipher cipher = cipher(key(7));
    assertThrows(CredentialCipher.CredentialException.class, () -> cipher.decrypt(null, TEAM_ID));
    assertThrows(
        CredentialCipher.CredentialException.class, () -> cipher.decrypt(new byte[3], TEAM_ID));
  }

  @Test
  void checkKeyReportsAWorkingKey() {
    cipher(key(8)).checkKey();
  }
}
