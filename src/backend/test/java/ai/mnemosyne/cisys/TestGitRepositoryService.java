/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
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

  static void resetCalls() {
    CALLS.set(0);
  }

  static int calls() {
    return CALLS.get();
  }

  @Override
  protected Collection<String> remoteBranchRefs(String repositoryUrl) throws GitAPIException {
    CALLS.incrementAndGet();
    if (repositoryUrl.contains("unreachable")) throw new GitAPIException("unreachable") {};
    return List.of("refs/heads/main", "refs/heads/dev", "refs/tags/v1", "refs/heads/dev");
  }
}
