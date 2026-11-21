/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.api.errors.GitAPIException;

/** Replaces the real network lookup in {@link BranchResourceTest}. */
@Alternative
@ApplicationScoped
public class TestGitRepositoryService extends GitRepositoryService {
  private static final AtomicInteger CALLS = new AtomicInteger();
  private static final java.util.concurrent.atomic.AtomicReference<String> CREDENTIAL =
      new java.util.concurrent.atomic.AtomicReference<>();

  static void resetCalls() {
    CALLS.set(0);
    CREDENTIAL.set(null);
  }

  static int calls() {
    return CALLS.get();
  }

  static String credential() {
    return CREDENTIAL.get();
  }

  @Override
  protected String token(Project project) {
    // A team secret goes through the real resolver, so its decryption is exercised.
    if (project.teamId != null) {
      return super.token(project);
    }
    // The test alternative never reads the environment.
    if ("CISYS_GIT_MISSING".equals(project.credentialRef)) {
      throw new BranchLookupException("Missing Git credential", null);
    }
    return project.credentialRef == null ? null : "test-token";
  }

  @Override
  protected Collection<String> remoteBranchRefs(String repositoryUrl, String token)
      throws GitAPIException {
    CALLS.incrementAndGet();
    CREDENTIAL.set(token);
    if (repositoryUrl.contains("unreachable")) {
      throw new GitAPIException("unreachable") {};
    }
    return List.of("refs/heads/main", "refs/heads/dev", "refs/tags/v1", "refs/heads/dev");
  }
}
