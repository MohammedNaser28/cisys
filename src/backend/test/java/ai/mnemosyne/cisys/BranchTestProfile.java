/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Set;

public class BranchTestProfile implements QuarkusTestProfile {
  @Override
  public Set<Class<?>> getEnabledAlternatives() {
    return Set.of(TestGitRepositoryService.class);
  }
}
