/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRepositoryServiceTest {
  @TempDir Path temp;

  @Test
  void keepsOnlySortedUniqueBranchNames() {
    assertEquals(
        List.of("dev", "main", "release/1.0"),
        GitRepositoryService.branchNames(
            List.of(
                "refs/heads/main",
                "refs/heads/dev",
                "refs/tags/v1",
                "refs/heads/dev",
                "refs/heads/release/1.0")));
  }

  @Test
  void ignoresMalformedRefs() {
    assertEquals(
        List.of("main"),
        GitRepositoryService.branchNames(
            java.util.Arrays.asList(null, "", "refs/heads/", "refs/heads/main")));
  }

  @Test
  void listsMoreThanFiftyBranchesFromLocalBareRepository() throws Exception {
    Path work = Files.createDirectory(temp.resolve("work"));
    Path bare = temp.resolve("bare");
    try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("master").call()) {
      Files.writeString(work.resolve("README.md"), "test");
      git.add().addFilepattern(".").call();
      git.commit()
          .setMessage("initial")
          .setAuthor("Test", "test@example.com")
          .setCommitter("Test", "test@example.com")
          .call();
      for (int i = 0; i < 60; i++) {
        git.branchCreate()
            .setName("branch-" + String.format("%02d", i))
            .setStartPoint("HEAD")
            .call();
      }
    }
    try (Git ignored =
        Git.cloneRepository()
            .setURI(work.toUri().toString())
            .setDirectory(bare.toFile())
            .setBare(true)
            .call()) {
      // The clone creates the bare remote.
    }
    GitRepositoryService service = new GitRepositoryService();
    List<String> names =
        GitRepositoryService.branchNames(service.remoteBranchRefs(bare.toUri().toString()));
    assertEquals(61, names.size());
    assertEquals("branch-00", names.get(0));
    assertTrue(names.contains("branch-59"));
    assertEquals("master", names.get(60));
  }

  @Test
  void doesNotFollowHttpRedirects() throws Exception {
    AtomicInteger redirected = new AtomicInteger();
    HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    target.createContext(
        "/",
        exchange -> {
          redirected.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    target.start();
    HttpServer redirect = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    redirect.createContext(
        "/",
        exchange -> {
          exchange
              .getResponseHeaders()
              .add(
                  "Location",
                  "http://127.0.0.1:" + target.getAddress().getPort() + "/repo.git/info/refs");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    redirect.start();
    try {
      GitRepositoryService service = new GitRepositoryService();
      String url = "http://127.0.0.1:" + redirect.getAddress().getPort() + "/repo.git";
      // validate() only accepts github.com/gitlab.com, so the protected method is called directly.
      assertThrows(GitAPIException.class, () -> service.remoteBranchRefs(url));
      assertEquals(0, redirected.get());
    } finally {
      redirect.stop(0);
      target.stop(0);
    }
  }
}
