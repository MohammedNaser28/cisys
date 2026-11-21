/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pull request discovery and submission against real local repositories, without network. The test
 * configuration allows local file: URLs; the request path never produces them. The stored provider
 * name stands in for the github.com or gitlab.com host that a production URL has.
 */
@QuarkusTest
class PullRequestBuildTest {
  @Inject CommitPoller poller;
  @Inject PullRequestPoller pullRequests;
  @Inject BuildRunner runner;
  @Inject RepositoryMirror mirrors;
  @Inject GitRepositoryService git;

  @TempDir Path root;

  @BeforeEach
  void setUp() {
    poller = io.quarkus.arc.ClientProxy.unwrap(poller);
    pullRequests = io.quarkus.arc.ClientProxy.unwrap(pullRequests);
    runner = io.quarkus.arc.ClientProxy.unwrap(runner);
    mirrors = io.quarkus.arc.ClientProxy.unwrap(mirrors);
    git = io.quarkus.arc.ClientProxy.unwrap(git);
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Build.deleteAll();
          ProjectBranch.deleteAll();
          ProjectPullRequest.deleteAll();
          Project.deleteAll();
          Settings.deleteAll();
        });
    mirrors.deleteAll();
    mirrors.maxMib = 4096;
    mirrors.readFailure = (op, detail) -> false;
    poller.maxCommits = 1000;
    pullRequests.maxPullRequests = 200;
    runner.queueLimit = 100;
  }

  @AfterEach
  void tearDown() {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Build.deleteAll();
          ProjectBranch.deleteAll();
          ProjectPullRequest.deleteAll();
          Project.deleteAll();
          Settings.deleteAll();
        });
    mirrors.deleteAll();
  }

  // --- helpers ----------------------------------------------------------------------------------

  Path bare(String name) throws Exception {
    Path dir = Files.createDirectories(root.resolve(name));
    try (Git ignored =
        Git.init().setBare(true).setInitialBranch("main").setDirectory(dir.toFile()).call()) {
      return dir;
    }
  }

  Path clone(Path repository, String name) throws Exception {
    Path dir = root.resolve(name);
    try (Git ignored =
        Git.cloneRepository()
            .setURI(repository.toUri().toString())
            .setDirectory(dir.toFile())
            .call()) {
      return dir;
    }
  }

  String commit(Path repo, String path, String content, String message) throws Exception {
    Path file = repo.resolve(path);
    if (file.getParent() != null) {
      Files.createDirectories(file.getParent());
    }
    Files.writeString(file, content);
    try (Git git = Git.open(repo.toFile())) {
      git.add().addFilepattern(".").call();
      return git.commit()
          .setMessage(message)
          .setAuthor("tester", "t@example.com")
          .setCommitter("tester", "t@example.com")
          .call()
          .name();
    }
  }

  String workflow(Path repo, String message) throws Exception {
    return commit(repo, Workflow.PATH, "name: pr\ncontainer: alpine:3.23\n", message);
  }

  void push(Path work, String branch) throws Exception {
    try (Git git = Git.open(work.toFile())) {
      // Push the checked-out commit to the named branch, whatever the local default branch is.
      git.push().setRemote("origin").add("HEAD:refs/heads/" + branch).call();
    }
  }

  void remove(Path work, String path, String message) throws Exception {
    try (Git git = Git.open(work.toFile())) {
      git.rm().addFilepattern(path).call();
      git.commit()
          .setMessage(message)
          .setAuthor("tester", "t@example.com")
          .setCommitter("tester", "t@example.com")
          .call();
    }
  }

  void createBranch(Path work, String branch) throws Exception {
    try (Git git = Git.open(work.toFile())) {
      git.checkout().setCreateBranch(true).setName(branch).call();
    }
  }

  void ref(Path repository, PullRequestProvider provider, int number, String revision)
      throws Exception {
    try (Repository repo = Git.open(repository.toFile()).getRepository()) {
      RefUpdate update = repo.updateRef(provider.headRef(number));
      update.setNewObjectId(ObjectId.fromString(revision));
      RefUpdate.Result result = update.update();
      assertNotEquals(RefUpdate.Result.REJECTED, result);
      assertNotEquals(RefUpdate.Result.LOCK_FAILURE, result);
    }
  }

  void forceRef(Path repository, PullRequestProvider provider, int number, String revision)
      throws Exception {
    try (Repository repo = Git.open(repository.toFile()).getRepository()) {
      RefUpdate update = repo.updateRef(provider.headRef(number));
      update.setNewObjectId(ObjectId.fromString(revision));
      update.setForceUpdate(true);
      RefUpdate.Result result = update.update();
      assertNotEquals(RefUpdate.Result.REJECTED, result);
      assertNotEquals(RefUpdate.Result.LOCK_FAILURE, result);
    }
  }

  void deleteRef(Path repository, String name) throws Exception {
    try (Repository repo = Git.open(repository.toFile()).getRepository()) {
      RefUpdate update = repo.updateRef(name);
      update.setForceUpdate(true);
      update.delete();
    }
  }

  Long project(String name, Path repository) {
    return project(name, repository, PullRequestProvider.GITHUB);
  }

  Long project(String name, Path repository, PullRequestProvider provider) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project p = new Project();
          p.name = name;
          p.ownerSubject = "owner";
          p.repositoryUrl = repository.toUri().toString();
          p.privateRepository = false;
          p.pullRequestProvider = provider == null ? null : provider.name();
          p.persist();
          return p.id;
        });
  }

  void settings(boolean polling, boolean pullRequests) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Settings s = Settings.findById(1L);
          if (s == null) {
            s = new Settings();
            s.persist();
          }
          s.pollEnabled = polling;
          s.pullRequestsEnabled = pullRequests;
        });
  }

  void setPullRequests(boolean enabled) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Settings s = Settings.findById(1L);
          if (s == null) {
            s = new Settings();
            s.persist();
          }
          s.pullRequestsEnabled = enabled;
        });
  }

  void setPolling(boolean enabled) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Settings s = Settings.findById(1L);
          if (s == null) {
            s = new Settings();
            s.persist();
          }
          s.pollEnabled = enabled;
        });
  }

  List<Build> builds(Long projectId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> Build.<Build>find("projectId = ?1 order by number", projectId).list());
  }

  Build buildAt(Long projectId, String revision) {
    return builds(projectId).stream()
        .filter(b -> revision.equals(b.revision))
        .findFirst()
        .orElse(null);
  }

  Build buildById(Long buildId) {
    return QuarkusTransaction.requiringNew().call(() -> Build.<Build>findById(buildId));
  }

  void setBuildStatus(Long buildId, Build.Status status) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Build b = Build.findById(buildId);
          b.status = status;
          if (status == Build.Status.RUNNING) {
            b.startedAt = java.time.Instant.now();
          }
          if (status == Build.Status.PASSED || status == Build.Status.FAILED) {
            b.finishedAt = java.time.Instant.now();
          }
        });
  }

  Long storedBuild(
      Long projectId, String revision, Build.Trigger trigger, Build.Status status, Integer pr) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Build b = new Build();
          b.projectId = projectId;
          b.number = Build.nextNumber(projectId);
          b.status = status;
          b.trigger = trigger;
          b.revision = revision;
          b.branch = "feature2";
          b.pullRequest = pr;
          b.pullRequestProvider = trigger == Build.Trigger.PULL_REQUEST ? "GITHUB" : null;
          b.persist();
          return b.id;
        });
  }

  ProjectPullRequest cursor(Long projectId, int number) {
    return QuarkusTransaction.requiringNew().call(() -> ProjectPullRequest.of(projectId, number));
  }

  Long manualBuild(Long projectId, String revision, Build.Status status) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Build build = new Build();
          build.projectId = projectId;
          build.number = Build.nextNumber(projectId);
          build.status = status;
          build.trigger = Build.Trigger.MANUAL;
          build.revision = revision;
          build.branch = "main";
          build.persist();
          return build.id;
        });
  }

  Long baseline(Long projectId) {
    Integer value =
        QuarkusTransaction.requiringNew()
            .call(() -> Project.<Project>findById(projectId).pullRequestBaseline);
    return value == null ? null : value.longValue();
  }

  /** Seeds the project with one existing pull request, then adds a new one for the tests. */
  Long seeded(String name) throws Exception {
    Path repo = bare(name);
    Path work = clone(repo, name + "-work");
    String head = workflow(work, "workflow");
    push(work, "main");
    ref(repo, PullRequestProvider.GITHUB, 1, head);
    Long id = project(name, repo);
    settings(false, true);
    assertEquals(0, poller.poll());
    assertEquals(1, baseline(id));
    return id;
  }

  /** Adds a new branch and pull request above the baseline and returns its head. */
  String newPullRequest(Path repository, Path work, int number, String branch) throws Exception {
    createBranch(work, branch);
    String head = workflow(work, "workflow " + branch);
    push(work, branch);
    ref(repository, PullRequestProvider.GITHUB, number, head);
    return head;
  }

  // --- settings ---------------------------------------------------------------------------------

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void pullRequestSettingIsOffByDefaultAndAdminOnly() {
    given()
        .get("/api/settings/pull-requests")
        .then()
        .statusCode(200)
        .body("enabled", equalTo(false));
    given()
        .contentType("application/json")
        .body(Map.of("enabled", true))
        .put("/api/settings/pull-requests")
        .then()
        .statusCode(200)
        .body("enabled", equalTo(true));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  void pullRequestSettingRejectsNonAdmins() {
    given().get("/api/settings/pull-requests").then().statusCode(403);
  }

  // --- discovery --------------------------------------------------------------------------------

  @Test
  void providerParsingAcceptsOnlyHeadRefsWithAsciiDigits() {
    assertEquals(12, PullRequestProvider.GITHUB.number("refs/pull/12/head"));
    assertEquals(3, PullRequestProvider.GITLAB.number("refs/merge-requests/3/head"));
    assertNull(PullRequestProvider.GITHUB.number("refs/pull/12/merge"));
    assertNull(PullRequestProvider.GITHUB.number("refs/pull//head"));
    assertNull(PullRequestProvider.GITHUB.number("refs/pull/1x/head"));
    assertNull(PullRequestProvider.GITHUB.number("refs/merge-requests/1/head"));
    assertNull(PullRequestProvider.GITLAB.number("refs/pull/1/head"));
    assertNull(PullRequestProvider.GITHUB.number(null));
    assertEquals("PR #1", PullRequestProvider.GITHUB.label(1));
    assertEquals("MR !1", PullRequestProvider.GITLAB.label(1));
  }

  @Test
  void jgitListsPullRequestRefsAndIgnoresMergeAndMalformedRefs() throws Exception {
    Path repo = bare("refs");
    Path work = clone(repo, "refs-work");
    String head = workflow(work, "workflow");
    push(work, "main");
    ref(repo, PullRequestProvider.GITHUB, 3, head);
    ref(repo, PullRequestProvider.GITHUB, 4, head);
    ref(repo, PullRequestProvider.GITLAB, 5, head);
    ref(repo, PullRequestProvider.GITHUB, 6, head);
    deleteRef(repo, PullRequestProvider.GITHUB.headRef(6));
    ref(repo, PullRequestProvider.GITHUB, 7, head);
    try (Repository bareRepo = Git.open(repo.toFile()).getRepository()) {
      RefUpdate merge = bareRepo.updateRef("refs/pull/8/merge");
      merge.setNewObjectId(ObjectId.fromString(head));
      merge.update();
      RefUpdate malformed = bareRepo.updateRef("refs/pull/x/head");
      malformed.setNewObjectId(ObjectId.fromString(head));
      malformed.update();
    }

    Project p =
        QuarkusTransaction.requiringNew()
            .call(() -> {
              Project row = new Project();
              row.repositoryUrl = repo.toUri().toString();
              row.pullRequestProvider = PullRequestProvider.GITHUB.name();
              return row;
            });
    Map<Integer, String> heads = git.listPullRequests(p);
    assertEquals(List.of(3, 4, 7), List.copyOf(heads.keySet()));
    assertTrue(heads.values().stream().allMatch(head::equals));
  }

  @Test
  void firstEnableSeedsAndBuildsNothing() throws Exception {
    Path repo = bare("seed");
    Path work = clone(repo, "seed-work");
    String head = workflow(work, "workflow");
    push(work, "main");
    ref(repo, PullRequestProvider.GITHUB, 7, head);
    Long id = project("seed", repo);
    settings(false, true);

    assertEquals(0, poller.poll());
    assertTrue(builds(id).isEmpty());
    assertEquals(7, baseline(id));
    ProjectPullRequest row = cursor(id, 7);
    assertEquals(head, row.lastSeenRevision);
    assertEquals(head, row.lastSubmittedRevision, "a seeded head never builds");
    assertTrue(row.refPresent);
  }

  @Test
  void aSeededPullRequestBuildsWhenItsHeadChangesLater() throws Exception {
    Path repo = bare("seedchange");
    Long id = seeded("seedchange");
    Path work = clone(repo, "seedchange-work2");
    String head = workflow(work, "new head on the seeded pull request");
    push(work, "main");
    ref(repo, PullRequestProvider.GITHUB, 1, head);

    assertEquals(1, poller.poll(), "a seeded pull request is not frozen at its seed head");
    assertEquals(1, builds(id).size());
    assertEquals(head, builds(id).get(0).revision);
    assertEquals(head, cursor(id, 1).lastSubmittedRevision);
  }

  @Test
  void newPullRequestAboveTheBaselineBuildsOnce() throws Exception {
    Path repo = bare("once");
    Long id = seeded("once");
    Path work = clone(repo, "once-work2");
    String head = newPullRequest(repo, work, 2, "feature2");

    assertEquals(1, poller.poll());
    List<Build> rows = builds(id);
    assertEquals(1, rows.size());
    assertEquals(Build.Trigger.PULL_REQUEST, rows.get(0).trigger);
    assertEquals(2, rows.get(0).pullRequest);
    assertEquals("GITHUB", rows.get(0).pullRequestProvider);
    assertEquals(head, rows.get(0).revision);
    assertEquals("feature2", rows.get(0).branch);
    assertNull(rows.get(0).credentialRef);

    assertEquals(0, poller.poll(), "the same head is not built twice");
    assertEquals(1, builds(id).size());
    assertEquals(head, cursor(id, 2).lastSubmittedRevision);
  }

  @Test
  void changedHeadBuildsTheNewShaAndCancelsTheQueuedOldOne() throws Exception {
    Path repo = bare("changed");
    Long id = seeded("changed");
    Path work = clone(repo, "changed-work2");
    String first = newPullRequest(repo, work, 2, "feature2");
    assertEquals(1, poller.poll());
    Build old = builds(id).get(0);
    assertEquals(first, old.revision);

    String second = commit(work, "second.txt", "second", "second");
    push(work, "feature2");
    ref(repo, PullRequestProvider.GITHUB, 2, second);
    assertEquals(1, poller.poll());

    List<Build> rows = builds(id);
    assertEquals(2, rows.size());
    assertEquals(Build.Status.CANCELLED, rows.get(0).status);
    assertEquals(second, rows.get(1).revision);
    assertEquals(second, cursor(id, 2).lastSubmittedRevision);
  }

  @Test
  void aRunningBuildOfTheOldHeadIsLeftAlone() throws Exception {
    Path repo = bare("running");
    Long id = seeded("running");
    Path work = clone(repo, "running-work2");
    newPullRequest(repo, work, 2, "feature2");
    assertEquals(1, poller.poll());
    QuarkusTransaction.requiringNew()
        .run(() -> Build.update("status = ?1 where projectId = ?2", Build.Status.RUNNING, id));

    String second = commit(work, "second.txt", "second", "second");
    push(work, "feature2");
    ref(repo, PullRequestProvider.GITHUB, 2, second);
    poller.poll();

    List<Build> rows = builds(id);
    assertEquals(Build.Status.RUNNING, rows.get(0).status);
    assertEquals(Build.Status.QUEUED, rows.get(1).status);
  }

  @Test
  void forcePushedBranchBuildsTheNewHead() throws Exception {
    Path repo = bare("force");
    Long id = seeded("force");
    Path work = clone(repo, "force-work2");
    newPullRequest(repo, work, 2, "feature2");
    poller.poll();

    // A rewritten branch: the old commit is no longer an ancestor of the new tip.
    String rewritten = commit(work, "rewritten.txt", "rewritten", "rewritten");
    push(work, "feature2");
    ref(repo, PullRequestProvider.GITHUB, 2, rewritten);
    assertEquals(1, poller.poll());
    assertEquals(rewritten, builds(id).get(1).revision);
  }

  @Test
  void aMissingRefCancelsItsBuildAndARestoredRefWithTheSameHeadBuildsAgain() throws Exception {
    Path repo = bare("missing");
    Long id = seeded("missing");
    Path work = clone(repo, "missing-work2");
    String head = newPullRequest(repo, work, 2, "feature2");
    poller.poll();
    Build queued = builds(id).get(0);

    deleteRef(repo, PullRequestProvider.GITHUB.headRef(2));
    assertEquals(0, poller.poll());
    assertFalse(cursor(id, 2).refPresent);
    assertNull(cursor(id, 2).lastSubmittedRevision, "the cancelled revision is submitted again");
    assertEquals(Build.Status.CANCELLED, builds(id).get(0).status);

    ref(repo, PullRequestProvider.GITHUB, 2, head);
    assertEquals(1, poller.poll(), "the same head is rebuilt after the ref returns");
    assertTrue(cursor(id, 2).refPresent);
    assertEquals(head, cursor(id, 2).lastSubmittedRevision);
    List<Build> rows = builds(id);
    assertEquals(1, rows.size(), "the cancelled row is revived, not duplicated");
    assertEquals(queued.id, rows.get(0).id);
    assertEquals(Build.Status.QUEUED, rows.get(0).status);
    assertEquals(head, rows.get(0).revision);
  }

  @Test
  void aHeadWithoutAReachableBranchIsNotBuilt() throws Exception {
    Path repo = bare("deleted");
    Long id = seeded("deleted");
    Path work = clone(repo, "deleted-work2");
    String head = newPullRequest(repo, work, 2, "feature2");
    deleteRef(repo, "refs/heads/feature2");

    // The pull request ref still exists in the repository, but no branch reaches the commit in the
    // mirror, so it is skipped. Recreate the branch to prove the same head then builds.
    assertEquals(0, poller.poll());
    assertTrue(builds(id).isEmpty());
    assertEquals(head, cursor(id, 2).lastSeenRevision);

    ref(repo, PullRequestProvider.GITHUB, 2, head);
    try (Git git = Git.open(repo.toFile())) {
      git.branchCreate().setName("feature2").setStartPoint(head).call();
    }
    assertEquals(1, poller.poll());
    assertEquals(head, builds(id).get(0).revision);
  }

  @Test
  void aHeadWithoutTheWorkflowFileIsNotBuilt() throws Exception {
    Path repo = bare("noworkflow");
    Long id = seeded("noworkflow");
    Path work = clone(repo, "noworkflow-work2");
    createBranch(work, "feature2");
    remove(work, Workflow.PATH, "remove workflow");
    String head = commit(work, "plain.txt", "plain", "no workflow");
    push(work, "feature2");
    ref(repo, PullRequestProvider.GITHUB, 2, head);

    assertEquals(0, poller.poll());
    assertTrue(builds(id).isEmpty());
    assertEquals(head, cursor(id, 2).lastSeenRevision);
    assertNull(cursor(id, 2).lastSubmittedRevision);
  }

  @Test
  void theWorkflowAtTheExactHeadIsUsed() throws Exception {
    Path repo = bare("exact");
    Long id = seeded("exact");
    Path work = clone(repo, "exact-work2");
    createBranch(work, "feature2");
    commit(work, "plain.txt", "plain", "no workflow");
    String head =
        commit(work, Workflow.PATH, "name: exact\ncontainer: alpine:3.23\n", "workflow at head");
    push(work, "feature2");
    ref(repo, PullRequestProvider.GITHUB, 2, head);

    assertEquals(1, poller.poll());
    Build build = builds(id).get(0);
    assertEquals(head, build.revision);
    assertTrue(mirrors.hasFile(id, head, Workflow.PATH));
  }

  @Test
  void aForkOnlyHeadIsNotFetchedAndNotBuilt() throws Exception {
    Path repo = bare("fork");
    Long id = seeded("fork");
    Path fork = bare("fork-source");
    Path forkWork = clone(fork, "fork-source-work");
    String forkHead = workflow(forkWork, "fork");
    push(forkWork, "main");

    // The object reaches the base repository through a temporary branch, so ls-remote can name it,
    // but the branch is removed before the mirror fetches: no local branch reaches the commit.
    try (Git git = Git.open(repo.toFile())) {
      git.fetch()
          .setRemote(fork.toUri().toString())
          .setRefSpecs(new org.eclipse.jgit.transport.RefSpec("+refs/heads/main:refs/heads/temp"))
          .call();
      git.branchDelete().setBranchNames("temp").setForce(true).call();
    }
    ref(repo, PullRequestProvider.GITHUB, 2, forkHead);

    assertEquals(0, poller.poll());
    assertTrue(builds(id).isEmpty());
    assertFalse(mirrors.has(id, forkHead), "a pull request head never enters the mirror");
    assertEquals(forkHead, cursor(id, 2).lastSeenRevision, "discovery still saw the head");
    assertTrue(cursor(id, 2).refPresent);
    assertNull(cursor(id, 2).lastSubmittedRevision, "the unreachable head was never submitted");
  }

  @Test
  void aCommitReachableFromABranchIsBuiltEvenWhenAProviderRefPointsAtIt() throws Exception {
    Path repo = bare("reachable");
    Long id = seeded("reachable");
    Path work = clone(repo, "reachable-work2");
    createBranch(work, "other");
    String head = workflow(work, "workflow");
    push(work, "other");
    ref(repo, PullRequestProvider.GITHUB, 2, head);

    assertEquals(1, poller.poll());
    assertEquals(head, builds(id).get(0).revision);
    assertEquals("other", builds(id).get(0).branch);
  }

  @Test
  void mainOnlyScopeDoesNotPreventAPullRequestBuild() throws Exception {
    Path repo = bare("scope");
    Long id = seeded("scope");
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Settings s = Settings.findById(1L);
          s.pollScope = Settings.Scope.MAIN_ONLY;
          s.pollEnabled = true;
        });
    Path work = clone(repo, "scope-work2");
    String head = newPullRequest(repo, work, 2, "feature2");

    assertEquals(1, poller.poll());
    assertEquals(
        head,
        builds(id).stream()
            .filter(b -> b.trigger == Build.Trigger.PULL_REQUEST)
            .findFirst()
            .orElseThrow()
            .revision);
  }

  @Test
  void aSharedHeadBuildIsKeptWhenOnlyOneOfItsPullRequestsMoves() throws Exception {
    Path repo = bare("sharedmove");
    Long id = seeded("sharedmove");
    Path work = clone(repo, "sharedmove-work2");
    String head = newPullRequest(repo, work, 2, "feature2");
    ref(repo, PullRequestProvider.GITHUB, 3, head);
    assertEquals(1, poller.poll());
    Build shared = builds(id).get(0);

    createBranch(work, "feature4");
    String moved = workflow(work, "moved head");
    push(work, "feature4");
    ref(repo, PullRequestProvider.GITHUB, 2, moved);
    assertEquals(1, poller.poll());

    List<Build> rows = builds(id);
    assertEquals(2, rows.size());
    assertEquals(Build.Status.QUEUED, rows.get(0).status, "the shared head is still wanted");
    assertEquals(head, rows.get(0).revision);
    assertEquals(shared.id, rows.get(0).id);
    assertEquals(moved, rows.get(1).revision);
  }

  @Test
  void aNewlyDiscoveredSharedHeadIsNotCancelledInTheSamePass() throws Exception {
    Path repo = bare("sharednew");
    Long id = seeded("sharednew");
    Path work = clone(repo, "sharednew-work2");
    createBranch(work, "feature1");
    String shared = workflow(work, "shared head");
    push(work, "feature1");
    ref(repo, PullRequestProvider.GITHUB, 1, shared);
    assertEquals(1, poller.poll(), "PR 1 builds its new head");
    List<Build> first = builds(id);
    assertEquals(1, first.size());
    Build queued = first.get(0);
    assertEquals(shared, queued.revision);
    assertEquals(Build.Status.QUEUED, queued.status);
    assertEquals(shared, cursor(id, 1).lastSubmittedRevision);

    // PR 1 moves off the shared head while PR 2 is discovered at it in the same pass.
    String moved = workflow(work, "moved head");
    push(work, "feature1");
    ref(repo, PullRequestProvider.GITHUB, 1, moved);
    ref(repo, PullRequestProvider.GITHUB, 2, shared);
    assertEquals(1, poller.poll(), "only PR 1 needs a new build");

    List<Build> rows = builds(id);
    assertEquals(2, rows.size(), "the shared head build is reused, not duplicated");
    Build sharedBuild =
        rows.stream().filter(b -> b.revision.equals(shared)).findFirst().orElseThrow();
    assertEquals(queued.id, sharedBuild.id);
    assertEquals(Build.Status.QUEUED, sharedBuild.status, "the shared head is still wanted");
    Build movedBuild =
        rows.stream().filter(b -> b.revision.equals(moved)).findFirst().orElseThrow();
    assertEquals(Build.Status.QUEUED, movedBuild.status);
    assertEquals(1, movedBuild.pullRequest);
    assertEquals(moved, cursor(id, 1).lastSubmittedRevision);
    assertEquals(shared, cursor(id, 2).lastSubmittedRevision);

    assertEquals(0, poller.poll(), "a repeated pass adds nothing");
    assertEquals(2, builds(id).size());
  }

  @Test
  void theLastSharedConsumerMovingCancelsTheBuildInALaterPass() throws Exception {
    Path repo = bare("sharedowner");
    Long id = seeded("sharedowner");
    Path work = clone(repo, "sharedowner-work2");
    String head = newPullRequest(repo, work, 2, "feature2");
    ref(repo, PullRequestProvider.GITHUB, 3, head);
    assertEquals(1, poller.poll());
    Build shared = buildAt(id, head);
    assertEquals(Build.Status.QUEUED, shared.status);
    assertEquals(2, shared.pullRequest, "the first submitter owns the row");

    createBranch(work, "feature4");
    String ownerMoved = workflow(work, "owner moved");
    push(work, "feature4");
    ref(repo, PullRequestProvider.GITHUB, 2, ownerMoved);
    assertEquals(1, poller.poll());
    assertEquals(Build.Status.QUEUED, buildAt(id, head).status, "PR 3 still needs the shared head");
    assertEquals(shared.id, buildAt(id, head).id);
    assertEquals(Build.Status.QUEUED, buildAt(id, ownerMoved).status);
    assertEquals(ownerMoved, cursor(id, 2).lastSubmittedRevision);
    assertEquals(head, cursor(id, 3).lastSubmittedRevision);

    createBranch(work, "feature5");
    String lastMoved = workflow(work, "last moved");
    push(work, "feature5");
    ref(repo, PullRequestProvider.GITHUB, 3, lastMoved);
    assertEquals(1, poller.poll());
    assertEquals(
        Build.Status.CANCELLED,
        buildAt(id, head).status,
        "no eligible pull request needs the shared head any more");
    assertEquals(Build.Status.QUEUED, buildAt(id, ownerMoved).status);
    assertEquals(Build.Status.QUEUED, buildAt(id, lastMoved).status);
    assertEquals(3, buildAt(id, lastMoved).pullRequest);
    assertEquals(ownerMoved, cursor(id, 2).lastSubmittedRevision);
    assertEquals(lastMoved, cursor(id, 3).lastSubmittedRevision);

    assertEquals(0, poller.poll(), "a repeated pass changes nothing");
    assertEquals(3, builds(id).size());
  }

  @Test
  void bothSharedConsumersMovingInOnePassCancelTheBuildOnce() throws Exception {
    Path repo = bare("sharedboth");
    Long id = seeded("sharedboth");
    Path work = clone(repo, "sharedboth-work2");
    String head = newPullRequest(repo, work, 2, "feature2");
    ref(repo, PullRequestProvider.GITHUB, 3, head);
    assertEquals(1, poller.poll());
    Build shared = buildAt(id, head);

    createBranch(work, "feature4");
    String second = workflow(work, "second head");
    push(work, "feature4");
    createBranch(work, "feature5");
    String third = workflow(work, "third head");
    push(work, "feature5");
    ref(repo, PullRequestProvider.GITHUB, 2, second);
    ref(repo, PullRequestProvider.GITHUB, 3, third);

    assertEquals(2, poller.poll(), "both new heads are queued");
    assertEquals(Build.Status.CANCELLED, buildAt(id, head).status);
    assertEquals(shared.id, buildAt(id, head).id);
    assertEquals(Build.Status.QUEUED, buildAt(id, second).status);
    assertEquals(Build.Status.QUEUED, buildAt(id, third).status);
    assertEquals(2, buildAt(id, second).pullRequest);
    assertEquals(3, buildAt(id, third).pullRequest);
    assertEquals(second, cursor(id, 2).lastSubmittedRevision);
    assertEquals(third, cursor(id, 3).lastSubmittedRevision);

    assertEquals(0, poller.poll());
    assertEquals(3, builds(id).size());
  }

  @Test
  void theSharedBuildSurvivesTheOwnerDisappearingAndIsCancelledWhenTheLastConsumerMoves()
      throws Exception {
    Path repo = bare("sharedclose");
    Long id = seeded("sharedclose");
    Path work = clone(repo, "sharedclose-work2");
    String head = newPullRequest(repo, work, 2, "feature2");
    ref(repo, PullRequestProvider.GITHUB, 3, head);
    assertEquals(1, poller.poll());
    Build shared = buildAt(id, head);

    deleteRef(repo, PullRequestProvider.GITHUB.headRef(2));
    assertEquals(0, poller.poll(), "PR 3 still needs the shared head");
    assertEquals(Build.Status.QUEUED, buildAt(id, head).status);
    assertEquals(shared.id, buildAt(id, head).id);
    assertFalse(cursor(id, 2).refPresent);
    assertEquals(head, cursor(id, 2).lastSubmittedRevision);

    createBranch(work, "feature4");
    String moved = workflow(work, "last consumer moved");
    push(work, "feature4");
    ref(repo, PullRequestProvider.GITHUB, 3, moved);
    assertEquals(1, poller.poll());
    assertEquals(Build.Status.CANCELLED, buildAt(id, head).status);
    assertEquals(Build.Status.QUEUED, buildAt(id, moved).status);
    assertEquals(3, buildAt(id, moved).pullRequest);
    assertNull(cursor(id, 2).lastSubmittedRevision, "the cancelled revision is submitted again");
    assertEquals(head, cursor(id, 2).lastSeenRevision);
    assertEquals(moved, cursor(id, 3).lastSubmittedRevision);

    assertEquals(0, poller.poll());
  }

  @Test
  void aClosedNonOwnerDoesNotKeepTheSharedBuildAfterTheOwnerMoves() throws Exception {
    Path repo = bare("sharedphantom");
    Long id = seeded("sharedphantom");
    Path work = clone(repo, "sharedphantom-work2");
    String head = newPullRequest(repo, work, 2, "feature2");
    ref(repo, PullRequestProvider.GITHUB, 3, head);
    assertEquals(1, poller.poll());

    deleteRef(repo, PullRequestProvider.GITHUB.headRef(3));
    assertEquals(0, poller.poll(), "the owner still needs the shared head");
    assertEquals(Build.Status.QUEUED, buildAt(id, head).status);
    assertFalse(cursor(id, 3).refPresent);
    assertEquals(head, cursor(id, 3).lastSubmittedRevision);

    createBranch(work, "feature4");
    String moved = workflow(work, "owner moved");
    push(work, "feature4");
    ref(repo, PullRequestProvider.GITHUB, 2, moved);
    assertEquals(1, poller.poll());
    assertEquals(
        Build.Status.CANCELLED, buildAt(id, head).status, "a closed non-owner is not a consumer");
    assertEquals(Build.Status.QUEUED, buildAt(id, moved).status);
    assertNull(cursor(id, 3).lastSubmittedRevision);
    assertEquals(head, cursor(id, 3).lastSeenRevision);
    assertFalse(cursor(id, 3).refPresent);
    assertEquals(moved, cursor(id, 2).lastSubmittedRevision);
  }

  @Test
  void allConsumersDisappearingCancelsTheBuildAndAReturningHeadBuildsAgain() throws Exception {
    Path repo = bare("sharedall");
    Long id = seeded("sharedall");
    Path work = clone(repo, "sharedall-work2");
    String head = newPullRequest(repo, work, 2, "feature2");
    ref(repo, PullRequestProvider.GITHUB, 3, head);
    assertEquals(1, poller.poll());
    Build shared = buildAt(id, head);

    deleteRef(repo, PullRequestProvider.GITHUB.headRef(2));
    deleteRef(repo, PullRequestProvider.GITHUB.headRef(3));
    assertEquals(0, poller.poll());
    assertEquals(Build.Status.CANCELLED, buildAt(id, head).status);
    assertNull(cursor(id, 2).lastSubmittedRevision);
    assertNull(cursor(id, 3).lastSubmittedRevision);
    assertEquals(head, cursor(id, 2).lastSeenRevision);
    assertFalse(cursor(id, 2).refPresent);
    assertFalse(cursor(id, 3).refPresent);

    ref(repo, PullRequestProvider.GITHUB, 2, head);
    assertEquals(1, poller.poll(), "the restored same head is submitted again");
    List<Build> rows = builds(id);
    assertEquals(1, rows.size(), "the cancelled row is revived, not duplicated");
    assertEquals(shared.id, rows.get(0).id);
    assertEquals(Build.Status.QUEUED, rows.get(0).status);
    assertEquals(head, rows.get(0).revision);
    assertEquals(2, rows.get(0).pullRequest);
    assertEquals(head, cursor(id, 2).lastSubmittedRevision);
    assertEquals(head, cursor(id, 2).lastSeenRevision);
    assertNull(cursor(id, 3).lastSubmittedRevision);

    assertEquals(0, poller.poll());
  }

  @Test
  void aPullRequestReturningToItsCancelledHeadGetsAQueuedBuildAgain() throws Exception {
    Path repo = bare("sharedreturn");
    Long id = seeded("sharedreturn");
    Path work = clone(repo, "sharedreturn-work2");
    String head = newPullRequest(repo, work, 2, "feature2");
    assertEquals(1, poller.poll());
    Build original = buildAt(id, head);

    createBranch(work, "feature4");
    String moved = workflow(work, "moved");
    push(work, "feature4");
    ref(repo, PullRequestProvider.GITHUB, 2, moved);
    assertEquals(1, poller.poll());
    assertEquals(Build.Status.CANCELLED, buildAt(id, head).status);
    assertEquals(Build.Status.QUEUED, buildAt(id, moved).status);

    forceRef(repo, PullRequestProvider.GITHUB, 2, head);
    assertEquals(1, poller.poll(), "the old head is needed again");
    assertEquals(original.id, buildAt(id, head).id, "the cancelled row is reused");
    assertEquals(Build.Status.QUEUED, buildAt(id, head).status);
    assertEquals(2, buildAt(id, head).pullRequest);
    assertEquals(
        Build.Status.CANCELLED, buildAt(id, moved).status, "the abandoned head is dropped");
    assertEquals(head, cursor(id, 2).lastSubmittedRevision);

    assertEquals(0, poller.poll());
  }

  @Test
  void anIneligibleSharedHeadDoesNotKeepTheBuildAlive() throws Exception {
    Path repo = bare("sharedineligible");
    Long id = seeded("sharedineligible");
    Path work = clone(repo, "sharedineligible-work2");
    String head = newPullRequest(repo, work, 2, "feature2");
    ref(repo, PullRequestProvider.GITHUB, 3, head);
    assertEquals(1, poller.poll());
    assertEquals(Build.Status.QUEUED, buildAt(id, head).status);

    // PR 2 moves to a branch that does not contain the shared commit; PR 3 still points at it,
    // but no branch reaches it any more, so it is not eligible.
    Path other = clone(repo, "sharedineligible-work3");
    createBranch(other, "feature4");
    String moved = workflow(other, "moved");
    push(other, "feature4");
    forceRef(repo, PullRequestProvider.GITHUB, 2, moved);
    deleteRef(repo, "refs/heads/feature2");
    assertEquals(1, poller.poll());
    assertEquals(
        Build.Status.CANCELLED, buildAt(id, head).status, "an unreachable head is not a consumer");
    assertEquals(Build.Status.QUEUED, buildAt(id, moved).status);
    assertTrue(cursor(id, 3).refPresent);
    assertNull(cursor(id, 3).lastSubmittedRevision);
  }

  @Test
  void aRunningSharedBuildIsLeftAloneByReconciliation() throws Exception {
    Path repo = bare("sharedrunning");
    Long id = seeded("sharedrunning");
    Path work = clone(repo, "sharedrunning-work2");
    String head = newPullRequest(repo, work, 2, "feature2");
    assertEquals(1, poller.poll());
    Build running = buildAt(id, head);
    setBuildStatus(running.id, Build.Status.RUNNING);

    createBranch(work, "feature4");
    String moved = workflow(work, "moved");
    push(work, "feature4");
    ref(repo, PullRequestProvider.GITHUB, 2, moved);
    assertEquals(1, poller.poll());
    assertEquals(Build.Status.RUNNING, buildAt(id, head).status);
    assertEquals(Build.Status.QUEUED, buildAt(id, moved).status);
    assertEquals(moved, cursor(id, 2).lastSubmittedRevision);
  }

  @Test
  void aTerminalSharedBuildIsLeftAloneByReconciliation() throws Exception {
    Path repo = bare("sharedterminal");
    Long id = seeded("sharedterminal");
    Path work = clone(repo, "sharedterminal-work2");
    String head = newPullRequest(repo, work, 2, "feature2");
    assertEquals(1, poller.poll());
    Build finished = buildAt(id, head);
    setBuildStatus(finished.id, Build.Status.FAILED);

    createBranch(work, "feature4");
    String moved = workflow(work, "moved");
    push(work, "feature4");
    ref(repo, PullRequestProvider.GITHUB, 2, moved);
    assertEquals(1, poller.poll());
    assertEquals(Build.Status.FAILED, buildAt(id, head).status);
    assertEquals(Build.Status.QUEUED, buildAt(id, moved).status);
    assertEquals(moved, cursor(id, 2).lastSubmittedRevision);
  }

  @Test
  void reconciliationCancelsAStaleBuildWithoutANewMovedEvent() throws Exception {
    Path repo = bare("sharedretry");
    Long id = seeded("sharedretry");
    Path work = clone(repo, "sharedretry-work2");
    String head = newPullRequest(repo, work, 2, "feature2");
    assertEquals(1, poller.poll());
    createBranch(work, "feature4");
    String moved = workflow(work, "moved");
    push(work, "feature4");
    ref(repo, PullRequestProvider.GITHUB, 2, moved);
    assertEquals(1, poller.poll());
    assertEquals(Build.Status.CANCELLED, buildAt(id, head).status);

    // As if a previous pass advanced the cursor but failed to cancel an old queued build.
    createBranch(work, "stale");
    String stale = workflow(work, "stale");
    push(work, "stale");
    Long staleBuild = storedBuild(id, stale, Build.Trigger.PULL_REQUEST, Build.Status.QUEUED, 2);
    assertEquals(0, poller.poll(), "no cursor moved in this pass");
    assertEquals(Build.Status.CANCELLED, buildById(staleBuild).status);
    assertEquals(moved, cursor(id, 2).lastSubmittedRevision);
    assertEquals(Build.Status.QUEUED, buildAt(id, moved).status);
  }

  @Test
  void aMirrorReadFailureDoesNotCancelAStaleBuild() throws Exception {
    Path repo = bare("sharedreadfail");
    Long id = seeded("sharedreadfail");
    Path work = clone(repo, "sharedreadfail-work2");
    String head = newPullRequest(repo, work, 2, "feature2");
    assertEquals(1, poller.poll());
    createBranch(work, "feature4");
    String moved = workflow(work, "moved");
    push(work, "feature4");
    ref(repo, PullRequestProvider.GITHUB, 2, moved);

    mirrors.readFailure = (op, detail) -> op.equals("branchTips");
    assertEquals(0, poller.poll());
    assertEquals(Build.Status.QUEUED, buildAt(id, head).status);
    assertNull(buildAt(id, moved));
    assertEquals(head, cursor(id, 2).lastSeenRevision);

    mirrors.readFailure = (op, detail) -> false;
    assertEquals(1, poller.poll());
    assertEquals(Build.Status.CANCELLED, buildAt(id, head).status);
    assertEquals(Build.Status.QUEUED, buildAt(id, moved).status);
  }

  @Test
  void aListingFailureDoesNotCancelAStaleBuild() throws Exception {
    Path repo = bare("sharedlistfail");
    Long id = seeded("sharedlistfail");
    Path work = clone(repo, "sharedlistfail-work2");
    String head = newPullRequest(repo, work, 2, "feature2");
    assertEquals(1, poller.poll());
    createBranch(work, "feature4");
    String moved = workflow(work, "moved");
    push(work, "feature4");
    ref(repo, PullRequestProvider.GITHUB, 2, moved);

    Path broken = Files.createDirectory(root.resolve("not-a-repository"));
    QuarkusTransaction.requiringNew()
        .run(() -> Project.<Project>findById(id).repositoryUrl = broken.toUri().toString());
    assertEquals(0, poller.poll());
    assertEquals(Build.Status.QUEUED, buildAt(id, head).status);
    assertNull(buildAt(id, moved));
    assertEquals(head, cursor(id, 2).lastSeenRevision);
  }

  @Test
  void aFullQueueStillLetsReconciliationCancelTheStaleBuild() throws Exception {
    Path repo = bare("sharedfull");
    Long id = seeded("sharedfull");
    Path work = clone(repo, "sharedfull-work2");
    String head = newPullRequest(repo, work, 2, "feature2");
    assertEquals(1, poller.poll());
    createBranch(work, "feature4");
    String moved = workflow(work, "moved");
    push(work, "feature4");
    ref(repo, PullRequestProvider.GITHUB, 2, moved);

    runner.queueLimit = 0;
    assertEquals(0, poller.poll());
    assertEquals(Build.Status.CANCELLED, buildAt(id, head).status);
    assertNull(buildAt(id, moved));
    assertEquals(moved, cursor(id, 2).lastSeenRevision);
    assertNull(cursor(id, 2).lastSubmittedRevision);

    runner.queueLimit = 100;
    assertEquals(1, poller.poll());
    assertEquals(Build.Status.QUEUED, buildAt(id, moved).status);
    assertEquals(moved, cursor(id, 2).lastSubmittedRevision);
  }

  @Test
  void reconciliationLeavesPollAndManualBuildsAlone() throws Exception {
    Path repo = bare("sharedpoll");
    Long id = seeded("sharedpoll");
    Path work = clone(repo, "sharedpoll-work2");
    String head = newPullRequest(repo, work, 2, "feature2");
    Long poll = storedBuild(id, head, Build.Trigger.POLL, Build.Status.QUEUED, null);
    assertEquals(0, poller.poll(), "the queued poll build covers the head");
    assertEquals(Build.Status.QUEUED, buildById(poll).status);
    assertEquals(head, cursor(id, 2).lastSubmittedRevision);

    createBranch(work, "feature4");
    String moved = workflow(work, "moved");
    push(work, "feature4");
    ref(repo, PullRequestProvider.GITHUB, 2, moved);
    assertEquals(1, poller.poll());
    assertEquals(Build.Status.QUEUED, buildById(poll).status, "poll builds are never reconciled");
    assertEquals(Build.Trigger.POLL, buildById(poll).trigger);
    assertEquals(Build.Status.QUEUED, buildAt(id, moved).status);
  }

  @Test
  void aCancelledPullRequestRowDoesNotMaskAUsablePollBuild() throws Exception {
    Path repo = bare("sharedmask");
    Long id = seeded("sharedmask");
    Path work = clone(repo, "sharedmask-work2");
    String head = newPullRequest(repo, work, 2, "feature2");
    Long cancelled = storedBuild(id, head, Build.Trigger.PULL_REQUEST, Build.Status.CANCELLED, 2);
    Long poll = storedBuild(id, head, Build.Trigger.POLL, Build.Status.QUEUED, null);
    assertEquals(0, poller.poll(), "the usable poll build covers the head");
    assertEquals(Build.Status.CANCELLED, buildById(cancelled).status);
    assertEquals(Build.Status.QUEUED, buildById(poll).status);
    assertEquals(Build.Trigger.POLL, buildById(poll).trigger);
    assertEquals(head, cursor(id, 2).lastSubmittedRevision);
    assertEquals(2, builds(id).size(), "no duplicate build is queued");
  }

  @Test
  void aCancelledPullRequestRowDoesNotMaskAUsableManualBuild() throws Exception {
    Path repo = bare("sharedmanual");
    Long id = seeded("sharedmanual");
    Path work = clone(repo, "sharedmanual-work2");
    String head = newPullRequest(repo, work, 2, "feature2");
    Long cancelled = storedBuild(id, head, Build.Trigger.PULL_REQUEST, Build.Status.CANCELLED, 2);
    Long manual = manualBuild(id, head, Build.Status.RUNNING);
    assertEquals(0, poller.poll(), "the usable manual build covers the head");
    assertEquals(Build.Status.CANCELLED, buildById(cancelled).status);
    assertEquals(Build.Status.RUNNING, buildById(manual).status);
    assertEquals(Build.Trigger.MANUAL, buildById(manual).trigger);
    assertEquals(head, cursor(id, 2).lastSubmittedRevision);
    assertEquals(2, builds(id).size(), "no duplicate build is queued");
  }

  @Test
  void anIntentionallyCancelledPullRequestBuildIsNotRevived() throws Exception {
    Path repo = bare("sharedintentional");
    Long id = seeded("sharedintentional");
    Path work = clone(repo, "sharedintentional-work2");
    String head = newPullRequest(repo, work, 2, "feature2");
    assertEquals(1, poller.poll());
    Build cancelled = buildAt(id, head);
    assertTrue(runner.cancel(cancelled.id));
    assertEquals(Build.Status.CANCELLED, buildAt(id, head).status);
    assertTrue(buildAt(id, head).cancelRequested);

    createBranch(work, "feature4");
    String moved = workflow(work, "moved");
    push(work, "feature4");
    ref(repo, PullRequestProvider.GITHUB, 2, moved);
    assertEquals(1, poller.poll());

    forceRef(repo, PullRequestProvider.GITHUB, 2, head);
    assertEquals(0, poller.poll(), "an intentional cancellation is not revived");
    assertEquals(Build.Status.CANCELLED, buildAt(id, head).status);
    assertTrue(buildAt(id, head).cancelRequested);
    assertEquals(Build.Status.CANCELLED, buildAt(id, moved).status);
    assertEquals(head, cursor(id, 2).lastSubmittedRevision);
  }

  @Test
  void aGitLabMergeRequestIsDiscoveredAndBuilt() throws Exception {
    Path repo = bare("gitlab");
    Path work = clone(repo, "gitlab-work");
    String first = workflow(work, "workflow");
    push(work, "main");
    ref(repo, PullRequestProvider.GITLAB, 1, first);
    Long id = project("gitlab", repo, PullRequestProvider.GITLAB);
    settings(false, true);
    assertEquals(0, poller.poll());

    createBranch(work, "feature");
    String second = workflow(work, "merge request");
    push(work, "feature");
    ref(repo, PullRequestProvider.GITLAB, 2, second);

    assertEquals(1, poller.poll());
    Build build = builds(id).get(0);
    assertEquals(second, build.revision);
    assertEquals(Build.Trigger.PULL_REQUEST, build.trigger);
    assertEquals(2, build.pullRequest);
    assertEquals("GITLAB", build.pullRequestProvider);
  }

  @Test
  void aPollBuildOfTheSameShaKeepsItsLabelAndIsNotDuplicated() throws Exception {
    Path repo = bare("dedup");
    Path work = clone(repo, "dedup-work");
    String first = workflow(work, "workflow");
    push(work, "main");
    ref(repo, PullRequestProvider.GITHUB, 1, first);
    Long id = project("dedup", repo);
    settings(true, true);
    assertEquals(0, poller.poll(), "both features seed");

    String second = commit(work, "second.txt", "second", "second");
    push(work, "main");
    ref(repo, PullRequestProvider.GITHUB, 2, second);
    assertEquals(1, poller.poll());

    List<Build> rows = builds(id);
    assertEquals(1, rows.size(), "the poll build already covers this commit");
    assertEquals(Build.Trigger.POLL, rows.get(0).trigger);
    assertNull(rows.get(0).pullRequest);
    assertEquals(second, cursor(id, 2).lastSubmittedRevision);
  }

  @Test
  void aManualBuildOfTheSameCommitNeverStopsThePoller() throws Exception {
    Path repo = bare("manual");
    Path work = clone(repo, "manual-work");
    workflow(work, "workflow");
    push(work, "main");
    Long id = project("manual", repo);
    settings(true, false);
    assertEquals(0, poller.poll(), "the branch seeds");

    String second = commit(work, "second.txt", "second", "second");
    Long manual = manualBuild(id, second, Build.Status.FAILED);
    push(work, "main");

    assertEquals(1, poller.poll(), "a manual build does not cover a polled commit");
    List<Build> rows = builds(id);
    assertEquals(2, rows.size());
    assertEquals(second, rows.get(1).revision);
    assertEquals(Build.Trigger.POLL, rows.get(1).trigger);
    assertEquals(Build.Status.QUEUED, rows.get(1).status);
    assertEquals(
        Build.Status.FAILED,
        QuarkusTransaction.requiringNew().call(() -> Build.<Build>findById(manual).status));
  }

  @Test
  void aBranchPollDoesNotDuplicateAShaAlreadyBuiltForAPullRequest() throws Exception {
    Path repo = bare("polldedup");
    Path work = clone(repo, "polldedup-work");
    String first = workflow(work, "workflow");
    push(work, "main");
    ref(repo, PullRequestProvider.GITHUB, 1, first);
    Long id = project("polldedup", repo);
    settings(true, true);
    assertEquals(0, poller.poll(), "both features seed");

    String second = commit(work, "second.txt", "second", "second");
    push(work, "main");
    ref(repo, PullRequestProvider.GITHUB, 2, second);

    // Discover the pull request first, so its build exists when the branch poll sees the commit.
    var lock = mirrors.lock(id);
    lock.lock();
    try {
      Project p = QuarkusTransaction.requiringNew().call(() -> Project.<Project>findById(id));
      mirrors.fetch(p);
      assertEquals(1, pullRequests.pollProject(p));
    } finally {
      lock.unlock();
    }

    assertEquals(0, poller.poll(), "the pull request build already covers the branch commit");
    List<Build> rows = builds(id);
    assertEquals(1, rows.size());
    assertEquals(Build.Trigger.PULL_REQUEST, rows.get(0).trigger);
    assertEquals(second, rows.get(0).revision);
  }

  @Test
  void twoPullRequestsWithTheSameHeadShareOneBuild() throws Exception {
    Path repo = bare("share");
    Long id = seeded("share");
    Path work = clone(repo, "share-work2");
    String head = newPullRequest(repo, work, 2, "feature2");
    ref(repo, PullRequestProvider.GITHUB, 3, head);

    assertEquals(1, poller.poll());
    assertEquals(1, builds(id).size());
    assertEquals(head, cursor(id, 2).lastSubmittedRevision);
    assertEquals(head, cursor(id, 3).lastSubmittedRevision);
  }

  @Test
  void aFailedBuildIsNotRetriedAutomatically() throws Exception {
    Path repo = bare("failed");
    Long id = seeded("failed");
    Path work = clone(repo, "failed-work2");
    newPullRequest(repo, work, 2, "feature2");
    poller.poll();
    QuarkusTransaction.requiringNew()
        .run(() -> Build.update("status = ?1 where projectId = ?2", Build.Status.FAILED, id));

    assertEquals(0, poller.poll());
    assertEquals(1, builds(id).size());
    assertEquals(
        builds(id).get(0).revision,
        cursor(id, 2).lastSubmittedRevision,
        "the failed head is not expected again");
  }

  @Test
  void aFullQueueKeepsTheHeadForTheNextTick() throws Exception {
    Path repo = bare("full");
    Long id = seeded("full");
    Path work = clone(repo, "full-work2");
    String head = newPullRequest(repo, work, 2, "feature2");

    runner.queueLimit = 0;
    assertEquals(0, poller.poll());
    assertTrue(builds(id).isEmpty());
    assertNull(cursor(id, 2).lastSubmittedRevision);

    runner.queueLimit = 100;
    assertEquals(1, poller.poll());
    assertEquals(head, builds(id).get(0).revision);
  }

  @Test
  void aListingFailureChangesNothing() throws Exception {
    Path repo = bare("listing");
    Long id = seeded("listing");
    Path work = clone(repo, "listing-work2");
    newPullRequest(repo, work, 2, "feature2");
    String seededHead = cursor(id, 1).lastSubmittedRevision;
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Project p = Project.findById(id);
          p.repositoryUrl = root.resolve("does-not-exist.git").toUri().toString();
        });

    assertEquals(0, poller.poll());
    assertTrue(builds(id).isEmpty());
    assertNull(cursor(id, 2));
    assertEquals(1, baseline(id), "a listing failure keeps the existing baseline");
    assertEquals(seededHead, cursor(id, 1).lastSubmittedRevision);
  }

  @Test
  void aMirrorReadFailureAdvancesNothing() throws Exception {
    Path repo = bare("readfail");
    Long id = seeded("readfail");
    Path work = clone(repo, "readfail-work2");
    newPullRequest(repo, work, 2, "feature2");
    mirrors.readFailure = (op, detail) -> op.equals("branchTips");

    assertEquals(0, poller.poll());
    assertTrue(builds(id).isEmpty());
    assertNull(cursor(id, 2));
    assertEquals(1, baseline(id), "a mirror read failure keeps the existing baseline");
    assertNotNull(cursor(id, 1));
    mirrors.readFailure = (op, detail) -> false;
  }

  // --- lifecycle --------------------------------------------------------------------------------

  @Test
  void pullRequestBuildsRunWhilePollingIsOffAndKeepTheMirror() throws Exception {
    Path repo = bare("off");
    Long id = seeded("off");
    Path work = clone(repo, "off-work2");
    newPullRequest(repo, work, 2, "feature2");
    assertEquals(1, poller.poll());
    assertTrue(Files.isDirectory(mirrors.path(id)));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void disablingThroughTheEndpointClearsTheCursorsAndReEnablingReseeds() throws Exception {
    Path repo = bare("reseed");
    Long id = seeded("reseed");
    Path work = clone(repo, "reseed-work2");
    newPullRequest(repo, work, 2, "feature2");
    poller.poll();

    given()
        .contentType("application/json")
        .body(Map.of("enabled", false))
        .put("/api/settings/pull-requests")
        .then()
        .statusCode(200);
    assertNull(baseline(id));
    assertNull(cursor(id, 2));

    given()
        .contentType("application/json")
        .body(Map.of("enabled", true))
        .put("/api/settings/pull-requests")
        .then()
        .statusCode(200);
    String third = newPullRequest(repo, work, 3, "feature3");
    assertEquals(0, poller.poll(), "re-enabling seeds the current pull requests");
    assertEquals(3, baseline(id));
    assertEquals(third, cursor(id, 3).lastSubmittedRevision);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void changingTheSettingClearsCursorsEvenWhileAProjectPollHoldsItsLock() throws Exception {
    Path repo = bare("lockedoff");
    Long id = seeded("lockedoff");
    Path work = clone(repo, "lockedoff-work2");
    newPullRequest(repo, work, 2, "feature2");
    poller.poll();
    assertNotNull(cursor(id, 2));

    var lock = mirrors.lock(id);
    lock.lock();
    try {
      long started = System.nanoTime();
      given()
          .contentType("application/json")
          .body(Map.of("enabled", false))
          .put("/api/settings/pull-requests")
          .then()
          .statusCode(200);
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      assertTrue(elapsedMillis < 3_000, "the setting must not wait for the project lock");
      assertNull(baseline(id));
      assertNull(cursor(id, 2));
    } finally {
      lock.unlock();
    }
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void changesMadeWhileDisabledAreNotBuiltAfterReEnabling() throws Exception {
    Path repo = bare("whiledisabled");
    Long id = seeded("whiledisabled");
    Path work = clone(repo, "whiledisabled-work2");
    String second = newPullRequest(repo, work, 2, "feature2");
    assertEquals(1, poller.poll());
    assertEquals(Build.Status.QUEUED, buildAt(id, second).status);

    given()
        .contentType("application/json")
        .body(Map.of("enabled", false))
        .put("/api/settings/pull-requests")
        .then()
        .statusCode(200);
    assertNull(baseline(id));
    assertNull(cursor(id, 2));

    String third = workflow(work, "moved while disabled");
    push(work, "feature2");
    ref(repo, PullRequestProvider.GITHUB, 2, third);
    createBranch(work, "feature3");
    String added = workflow(work, "added while disabled");
    push(work, "feature3");
    ref(repo, PullRequestProvider.GITHUB, 3, added);

    assertEquals(0, poller.poll(), "nothing is discovered while the feature is off");
    assertEquals(1, builds(id).size());
    assertNull(cursor(id, 2));
    assertNull(cursor(id, 3));

    given()
        .contentType("application/json")
        .body(Map.of("enabled", true))
        .put("/api/settings/pull-requests")
        .then()
        .statusCode(200);

    assertEquals(0, poller.poll(), "re-enabling seeds instead of building disabled changes");
    assertEquals(1, builds(id).size());
    assertEquals(3, baseline(id));
    assertEquals(third, cursor(id, 2).lastSubmittedRevision);
    assertEquals(added, cursor(id, 3).lastSubmittedRevision);

    String fourth = workflow(work, "after re-enable");
    push(work, "feature3");
    ref(repo, PullRequestProvider.GITHUB, 3, fourth);
    assertEquals(1, poller.poll(), "changes after re-enabling build normally");
    assertEquals(Build.Status.QUEUED, buildAt(id, fourth).status);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void repeatedDisableAndEnableIsIdempotentAndDoesNotBuildOldChanges() throws Exception {
    Path repo = bare("repeated");
    Long id = seeded("repeated");
    Path work = clone(repo, "repeated-work2");
    String second = newPullRequest(repo, work, 2, "feature2");
    assertEquals(1, poller.poll());

    for (int i = 0; i < 3; i++) {
      given()
          .contentType("application/json")
          .body(Map.of("enabled", false))
          .put("/api/settings/pull-requests")
          .then()
          .statusCode(200);
      assertNull(baseline(id));
      assertNull(cursor(id, 2));
      given()
          .contentType("application/json")
          .body(Map.of("enabled", true))
          .put("/api/settings/pull-requests")
          .then()
          .statusCode(200);
    }

    String third = workflow(work, "moved during the cycle");
    push(work, "feature2");
    ref(repo, PullRequestProvider.GITHUB, 2, third);
    assertEquals(0, poller.poll(), "the last enable seeds the current head");
    assertEquals(1, builds(id).size());
    assertNull(buildAt(id, third));
    assertEquals(third, cursor(id, 2).lastSubmittedRevision);
    assertEquals(third, cursor(id, 2).lastSeenRevision);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void aPollRacingADisableAndReEnableCannotSeedStaleHeads() throws Exception {
    Path repo = bare("racedisable");
    Long id = seeded("racedisable");
    Path work = clone(repo, "racedisable-work2");
    String second = newPullRequest(repo, work, 2, "feature2");

    CountDownLatch discovered = new CountDownLatch(1);
    CountDownLatch resume = new CountDownLatch(1);
    mirrors.readFailure =
        (operation, detail) -> {
          if (operation.equals("branchTips")) {
            discovered.countDown();
            try {
              resume.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          return false;
        };
    ExecutorService executor = Executors.newSingleThreadExecutor();
    String third;
    try {
      Future<Integer> pass = executor.submit(poller::poll);
      assertTrue(discovered.await(10, TimeUnit.SECONDS), "the poll reached discovery");
      given()
          .contentType("application/json")
          .body(Map.of("enabled", false))
          .put("/api/settings/pull-requests")
          .then()
          .statusCode(200);
      given()
          .contentType("application/json")
          .body(Map.of("enabled", true))
          .put("/api/settings/pull-requests")
          .then()
          .statusCode(200);
      third = workflow(work, "moved after the stale listing");
      push(work, "feature2");
      ref(repo, PullRequestProvider.GITHUB, 2, third);
      resume.countDown();
      assertEquals(0, pass.get(30, TimeUnit.SECONDS));
    } finally {
      resume.countDown();
      executor.shutdownNow();
      mirrors.readFailure = (operation, detail) -> false;
    }

    assertNull(baseline(id), "the racing pass must not seed a stale baseline");
    assertNull(cursor(id, 2), "the racing pass must not recreate cursors");
    assertTrue(builds(id).isEmpty());
    assertNull(buildAt(id, second));

    assertEquals(0, poller.poll(), "the next pass seeds the current head");
    assertTrue(builds(id).isEmpty());
    assertEquals(2, baseline(id));
    assertEquals(third, cursor(id, 2).lastSubmittedRevision);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void disablingPullRequestBuildsLeavesBranchPollingRunning() throws Exception {
    Path repo = bare("branchstays");
    Path work = clone(repo, "branchstays-work");
    String first = workflow(work, "workflow");
    push(work, "main");
    ref(repo, PullRequestProvider.GITHUB, 1, first);
    Long id = project("branchstays", repo);
    settings(true, true);
    assertEquals(0, poller.poll(), "both features seed");

    given()
        .contentType("application/json")
        .body(Map.of("enabled", false))
        .put("/api/settings/pull-requests")
        .then()
        .statusCode(200);
    assertNotNull(
        QuarkusTransaction.requiringNew().call(() -> ProjectBranch.of(id, "main")),
        "branch cursors are kept");
    assertNull(baseline(id));

    String second = commit(work, "second.txt", "second", "second");
    push(work, "main");
    assertEquals(1, poller.poll(), "branch polling keeps working");
    List<Build> rows = builds(id);
    assertEquals(1, rows.size());
    assertEquals(Build.Trigger.POLL, rows.get(0).trigger);
    assertEquals(second, rows.get(0).revision);
    assertNull(cursor(id, 1));
  }

  @Test
  void aProviderChangeResetsTheCursorsAndBaseline() throws Exception {
    Long id = seeded("provider");
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Project p = Project.findById(id);
          p.repositoryUrl = "https://github.com/example/repository";
          p.pullRequestProvider = "GITLAB";
        });
    String provider =
        QuarkusTransaction.requiringNew()
            .call(() -> pullRequests.ensureProvider(Project.<Project>findById(id)));
    assertEquals("GITHUB", provider);
    assertNull(cursor(id, 1));
    assertNull(baseline(id));
  }

  @Test
  void turningBothFeaturesOffDeletesTheMirrorAndCursors() throws Exception {
    Path repo = bare("delete");
    Long id = seeded("delete");
    assertTrue(Files.isDirectory(mirrors.path(id)));
    setPullRequests(false);

    poller.poll();
    assertFalse(Files.exists(mirrors.path(id)));
    assertNull(cursor(id, 1));
  }

  @Test
  void aQueuedPullRequestBuildKeepsTheMirrorWhenBothFeaturesAreOff() throws Exception {
    Path repo = bare("keep");
    Long id = seeded("keep");
    Path work = clone(repo, "keep-work2");
    newPullRequest(repo, work, 2, "feature2");
    poller.poll();
    assertEquals(1, builds(id).size());

    setPullRequests(false);
    poller.poll();
    assertTrue(
        Files.isDirectory(mirrors.path(id)), "the queued build reads the mirror at run time");
  }

  @Test
  void turningBranchPollingOffResetsItsCursorsButKeepsTheMirror() throws Exception {
    Path repo = bare("reset");
    Path work = clone(repo, "reset-work");
    workflow(work, "workflow");
    push(work, "main");
    Long id = project("reset", repo);
    settings(true, true);
    assertEquals(0, poller.poll());
    assertTrue(Files.isDirectory(mirrors.path(id)));
    assertEquals(java.util.Set.of("main"), mirrors.branches(id));
    assertNotNull(QuarkusTransaction.requiringNew().call(() -> ProjectBranch.of(id, "main")));

    setPolling(false);
    poller.poll();
    assertNull(QuarkusTransaction.requiringNew().call(() -> ProjectBranch.of(id, "main")));
    assertTrue(Files.isDirectory(mirrors.path(id)));
  }

  @Test
  void aPrivateProjectWithoutACredentialIsSkipped() throws Exception {
    Path repo = bare("private");
    Path work = clone(repo, "private-work");
    String head = workflow(work, "workflow");
    push(work, "main");
    ref(repo, PullRequestProvider.GITHUB, 1, head);
    Long id =
        QuarkusTransaction.requiringNew()
            .call(() -> {
              Project p = new Project();
              p.name = "private";
              p.ownerSubject = "owner";
              p.repositoryUrl = repo.toUri().toString();
              p.privateRepository = true;
              p.pullRequestProvider = "GITHUB";
              p.persist();
              return p.id;
            });
    settings(false, true);

    assertEquals(0, poller.poll());
    assertTrue(builds(id).isEmpty());
  }

  @Test
  void onlyTheNewestConfiguredNumberOfPullRequestsIsTracked() throws Exception {
    Path repo = bare("newest");
    Path work = clone(repo, "newest-work");
    String head = workflow(work, "workflow");
    push(work, "main");
    for (int n = 1; n <= 5; n++) {
      ref(repo, PullRequestProvider.GITHUB, n, head);
    }
    Long id = project("newest", repo);
    settings(false, true);
    pullRequests.maxPullRequests = 2;
    assertEquals(0, poller.poll());
    assertEquals(5, baseline(id));
    assertNotNull(cursor(id, 5));
    assertNotNull(cursor(id, 4));
    assertNull(cursor(id, 1));

    for (int n = 6; n <= 7; n++) {
      ref(repo, PullRequestProvider.GITHUB, n, head);
    }
    assertEquals(1, poller.poll());
    assertEquals(1, builds(id).size(), "both pull requests share the same head");
    assertEquals(head, cursor(id, 6).lastSubmittedRevision);
    assertEquals(head, cursor(id, 7).lastSubmittedRevision);
  }

  @Test
  void concurrentTicksQueueOneBuild() throws Exception {
    Path repo = bare("concurrent");
    Long id = seeded("concurrent");
    Path work = clone(repo, "concurrent-work2");
    newPullRequest(repo, work, 2, "feature2");

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> first =
          pool.submit(() -> {
            start.await();
            return poller.poll();
          });
      Future<Integer> second =
          pool.submit(() -> {
            start.await();
            return poller.poll();
          });
      start.countDown();
      int total = first.get(60, TimeUnit.SECONDS) + second.get(60, TimeUnit.SECONDS);
      assertEquals(1, total);
      assertEquals(1, builds(id).size());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void retentionDeletingTheBuildDoesNotRebuildTheHead() throws Exception {
    Path repo = bare("retention");
    Long id = seeded("retention");
    Path work = clone(repo, "retention-work2");
    newPullRequest(repo, work, 2, "feature2");
    poller.poll();
    QuarkusTransaction.requiringNew().run(() -> Build.deleteAll());

    assertEquals(0, poller.poll());
    assertTrue(builds(id).isEmpty());
    assertEquals(cursor(id, 2).lastSeenRevision, cursor(id, 2).lastSubmittedRevision);
  }

  @Test
  void thePullRequestUniqueIndexRejectsASecondBuildOfTheSameCommit() throws Exception {
    Path repo = bare("index");
    Path work = clone(repo, "index-work");
    String head = workflow(work, "workflow");
    push(work, "main");
    Long id = project("index", repo);
    runner.submitPullRequest(id, repo.toUri().toString(), "main", head, 1, "GITHUB");
    assertThrows(
        RuntimeException.class,
        () ->
            QuarkusTransaction.requiringNew()
                .run(() -> {
                  Build b = new Build();
                  b.projectId = id;
                  b.number = Build.nextNumber(id);
                  b.status = Build.Status.QUEUED;
                  b.trigger = Build.Trigger.PULL_REQUEST;
                  b.revision = head;
                  b.pullRequest = 2;
                  b.pullRequestProvider = "GITHUB";
                  b.persist();
                }));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "owner")})
  void projectDeletionRemovesItsPullRequestRows() throws Exception {
    Path repo = bare("deleteproject");
    Long id = seeded("deleteproject");
    assertNotNull(cursor(id, 1));

    given().when().delete("/api/projects/" + id).then().statusCode(204);
    assertNull(cursor(id, 1));
    assertNull(QuarkusTransaction.requiringNew().call(() -> Project.findById(id)));
  }

  @Test
  void aPullRequestBuildFailsClosedWithoutAMirrorAndNeverLooksUpACredential() throws Exception {
    Path workspace = Files.createDirectory(root.resolve("workspace"));
    IllegalArgumentException missing =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runner.prepareWorkspace(
                    workspace,
                    "file:" + root.resolve("no-such-repository.git"),
                    "main",
                    "unused-token",
                    "0000000000000000000000000000000000000000",
                    null,
                    1024,
                    100000,
                    () -> false,
                    true));
    assertTrue(missing.getMessage().contains("mirror is missing"));
  }
}
