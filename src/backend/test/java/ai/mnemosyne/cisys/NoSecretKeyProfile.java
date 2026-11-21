/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;
import java.util.Set;

/**
 * Starts the application without an encryption key, as a deployment that has not configured one.
 */
public class NoSecretKeyProfile implements QuarkusTestProfile {
  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of("cisys.secret-key", "");
  }

  @Override
  public Set<Class<?>> getEnabledAlternatives() {
    return Set.of(TestGitRepositoryService.class);
  }
}
