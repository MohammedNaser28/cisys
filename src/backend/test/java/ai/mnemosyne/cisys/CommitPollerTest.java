/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The poller against real local repositories, no network. file: URLs are accepted only because the
 * test profile sets cisys.poll.allow-local; the request path never produces them.
 */
@QuarkusTest
class CommitPollerTest {
  @Inject CommitPoller poller;
  @Inject BuildRunner runner;
  @Inject RepositoryMirror mirrors;

  @TempDir Path root;
  Path source;

  @BeforeEach
  void setUp() {
    poller = io.quarkus.arc.ClientProxy.unwrap(poller);
    runner = io.quarkus.arc.ClientProxy.unwrap(runner);
    mirrors = io.quarkus.arc.ClientProxy.unwrap(mirrors);
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Build.deleteAll();
          ProjectBranch.deleteAll();
          Project.deleteAll();
          Settings.deleteAll();
        });
    mirrors.deleteAll();
    mirrors.maxMib = 4096;
    mirrors.readFailure = (op, detail) -> false;
    poller.maxCommits = 1000;
    runner.queueLimit = 100;
  }

  @AfterEach
  void tearDown() {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Build.deleteAll();
          ProjectBranch.deleteAll();
          Project.deleteAll();
        });
    mirrors.deleteAll();
  }

  // --- helpers ----------------------------------------------------------------------------------

  Path newRepository(String name) throws Exception {
    Path dir = Files.createDirectory(root.resolve(name));
    try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
      return dir;
    }
  }

  String commit(Path repo, String path, byte[] content, String message) throws Exception {
    Path file = repo.resolve(path);
    if (file.getParent() != null) {
      Files.createDirectories(file.getParent());
    }
    Files.write(file, content);
    try (Git git = Git.open(repo.toFile())) {
      git.add().addFilepattern(".").call();
      RevCommit commit =
          git.commit()
              .setMessage(message)
              .setAuthor("tester", "t@example.com")
              .setCommitter("tester", "t@example.com")
              .call();
      return commit.name();
    }
  }

  String commit(Path repo, String path, String content, String message) throws Exception {
    return commit(repo, path, content.getBytes(java.nio.charset.StandardCharsets.UTF_8), message);
  }

  String workflow(Path repo, String message) throws Exception {
    return commit(repo, Workflow.PATH, "name: test\ncontainer: alpine:3.23\n", message);
  }

  /** A commit with a fixed timestamp, so ordering assertions are deterministic. */
  String commitAt(Path repo, String path, String content, String message, long epochSeconds)
      throws Exception {
    Path file = repo.resolve(path);
    if (file.getParent() != null) {
      Files.createDirectories(file.getParent());
    }
    Files.writeString(file, content);
    PersonIdent who =
        new PersonIdent(
            "tester", "t@example.com", Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    try (Git git = Git.open(repo.toFile())) {
      git.add().addFilepattern(".").call();
      return git.commit().setMessage(message).setAuthor(who).setCommitter(who).call().name();
    }
  }

  String remove(Path repo, String path, String message) throws Exception {
    try (Git git = Git.open(repo.toFile())) {
      git.rm().addFilepattern(path).call();
      RevCommit commit =
          git.commit()
              .setMessage(message)
              .setAuthor("tester", "t@example.com")
              .setCommitter("tester", "t@example.com")
              .call();
      return commit.name();
    }
  }

  Long project(String name, Path repo) {
    return project(name, repo.toUri().toString(), false, null);
  }

  Long project(String name, String url, boolean privateRepository, String credentialRef) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project p = new Project();
          p.name = name;
          p.ownerSubject = "owner";
          p.repositoryUrl = url;
          p.privateRepository = privateRepository;
          p.credentialRef = credentialRef;
          p.persist();
          ProjectMember m = new ProjectMember();
          m.projectId = p.id;
          m.subject = p.ownerSubject;
          m.role = ProjectMember.TeamRole.LEAD;
          m.createdAt = java.time.Instant.now();
          m.persist();
          return p.id;
        });
  }

  int builds(Long projectId) {
    return QuarkusTransaction.requiringNew()
        .call(() ->
            (int) Build.count("projectId = ?1 and trigger = ?2", projectId, Build.Trigger.POLL));
  }

  List<String> revisions(Long projectId) {
    return QuarkusTransaction.requiringNew()
        .call(() ->
            Build.<Build>find("projectId = ?1 order by number", projectId).list().stream()
                .map(b -> b.revision)
                .toList());
  }

  String cursor(Long projectId, String branch) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          ProjectBranch row = ProjectBranch.of(projectId, branch);
          return row == null ? null : row.lastSeenRevision;
        });
  }

  void set(Settings.Granularity granularity, Settings.Scope scope, boolean enabled) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Settings s = Settings.findById(1L);
          if (s == null) {
            s = new Settings();
            s.persist();
          }
          s.pollEnabled = enabled;
          s.pollGranularity = granularity;
          s.pollScope = scope;
        });
  }

  void finish(Long projectId) {
    QuarkusTransaction.requiringNew()
        .run(() ->
            Build.update(
                "status = ?1, finishedAt = ?2 where projectId = ?3 and status = ?4",
                Build.Status.PASSED,
                java.time.Instant.now(),
                projectId,
                Build.Status.QUEUED));
  }

  // --- tests ------------------------------------------------------------------------------------

  @Test
  void firstSightingSeedsTheTipAndQueuesNothing() throws Exception {
    source = newRepository("seed");
    String tip = workflow(source, "add workflow");
    Long id = project("seed", source);

    assertEquals(0, poller.poll());
    assertEquals(0, builds(id));
    assertEquals(tip, cursor(id, "main"));
  }

  @Test
  void threeNewCommitsQueueOldestFirst() throws Exception {
    source = newRepository("three");
    workflow(source, "add workflow");
    Long id = project("three", source);
    poller.poll(); // seed

    String first = commit(source, "a.txt", "a", "one");
    String second = commit(source, "b.txt", "b", "two");
    String third = commit(source, "c.txt", "c", "three");

    assertEquals(3, poller.poll());
    assertEquals(List.of(first, second, third), revisions(id));
    assertEquals(third, cursor(id, "main"));
  }

  @Test
  void tipOnlyQueuesJustTheTip() throws Exception {
    source = newRepository("tip");
    workflow(source, "add workflow");
    Long id = project("tip", source);
    poller.poll();
    commit(source, "a.txt", "a", "one");
    commit(source, "b.txt", "b", "two");
    String tip = commit(source, "c.txt", "c", "three");

    set(Settings.Granularity.TIP, Settings.Scope.WORKFLOW, true);
    assertEquals(1, poller.poll());
    assertEquals(List.of(tip), revisions(id));
    assertEquals(tip, cursor(id, "main"));
  }

  @Test
  void aReadFailureStopsTheBranchWithoutAdvancingOrPruning() throws Exception {
    source = newRepository("readfail");
    String base = workflow(source, "add workflow");
    Long id = project("readfail", source);
    poller.poll(); // seed
    String next = commit(source, "a.txt", "a", "one");

    mirrors.readFailure = (op, detail) -> op.equals("hasFile") && detail.equals(next);
    try {
      assertEquals(0, poller.poll());
      assertEquals(0, builds(id));
      assertEquals(base, cursor(id, "main"), "a read failure must not advance the cursor");
    } finally {
      mirrors.readFailure = (op, detail) -> false;
    }

    // Once the mirror is readable again the commit is queued, so nothing was lost.
    assertEquals(1, poller.poll());
    assertEquals(List.of(next), revisions(id));
    assertEquals(next, cursor(id, "main"));
  }

  @Test
  void aReadFailureDoesNotDropABranch() throws Exception {
    source = newRepository("readfail2");
    String base = workflow(source, "add workflow");
    Long id = project("readfail2", source);
    poller.poll(); // seed

    mirrors.readFailure = (op, detail) -> op.equals("hasFile") && detail.equals(base);
    try {
      assertEquals(0, poller.poll());
      assertEquals(base, cursor(id, "main"), "a read failure must not prune the cursor");
    } finally {
      mirrors.readFailure = (op, detail) -> false;
    }
    assertEquals(base, cursor(id, "main"));
  }

  @Test
  void forcePushQueuesTheNewTipOnly() throws Exception {
    source = newRepository("force");
    String oldTip = workflow(source, "add workflow");
    Long id = project("force", source);
    poller.poll(); // seed the old tip

    // An unrelated history with several commits, force-pushed over the branch. The old commit
    // object stays in the mirror (no garbage collection), so object presence cannot tell this
    // rewrite apart from a normal fast-forward; only reachability can.
    Path other = newRepository("force-other");
    workflow(other, "unrelated root");
    commit(other, "a.txt", "a", "one");
    String newTip = commit(other, "b.txt", "b", "two");
    try (Git git = Git.open(source.toFile())) {
      git.getRepository().getConfig().setBoolean("receive", null, "denyCurrentBranch", false);
      git.getRepository().getConfig().save();
    }
    try (Git git = Git.open(other.toFile())) {
      git.push()
          .setRemote(source.toUri().toString())
          .add("refs/heads/main:refs/heads/main")
          .setForce(true)
          .call();
    }

    assertTrue(mirrors.has(id, oldTip), "the old commit is still an object in the mirror");
    assertEquals(1, poller.poll());
    assertEquals(List.of(newTip), revisions(id));
    assertEquals(newTip, cursor(id, "main"));
    assertNotEquals(oldTip, newTip);
  }

  @Test
  void commitWithoutWorkflowIsSkippedButTheCursorAdvances() throws Exception {
    source = newRepository("skip");
    workflow(source, "add workflow");
    Long id = project("skip", source);
    poller.poll();

    remove(source, Workflow.PATH, "no workflow");
    String tip = workflow(source, "workflow again");

    assertEquals(1, poller.poll());
    assertEquals(List.of(tip), revisions(id));
    assertEquals(tip, cursor(id, "main"));
  }

  @Test
  void deletedBranchDropsItsCursor() throws Exception {
    source = newRepository("deleted");
    workflow(source, "add workflow");
    Long id = project("deleted", source);
    poller.poll();
    try (Git git = Git.open(source.toFile())) {
      git.checkout().setCreateBranch(true).setName("dev").setStartPoint("main").call();
    }
    poller.poll();
    assertNotNull(cursor(id, "dev"));

    try (Git git = Git.open(source.toFile())) {
      git.checkout().setName("main").call();
      git.branchDelete().setBranchNames("dev").setForce(true).call();
    }
    assertEquals(0, poller.poll());
    assertNull(cursor(id, "dev"));
    assertNotNull(cursor(id, "main"));
  }

  @Test
  void branchWithoutWorkflowAtItsTipIsIgnored() throws Exception {
    source = newRepository("noci");
    workflow(source, "add workflow");
    Long id = project("noci", source);
    try (Git git = Git.open(source.toFile())) {
      git.checkout().setCreateBranch(true).setName("feature").setStartPoint("main").call();
    }
    commit(source, "x.txt", "x", "no workflow here");
    remove(source, Workflow.PATH, "drop the workflow");
    poller.poll();
    assertNull(cursor(id, "feature"));
  }

  @Test
  void mainOnlyScopeWatchesMainAndMaster() throws Exception {
    source = newRepository("mainonly");
    workflow(source, "add workflow");
    Long id = project("mainonly", source);
    try (Git git = Git.open(source.toFile())) {
      git.checkout().setCreateBranch(true).setName("feature").setStartPoint("main").call();
    }
    workflow(source, "feature workflow");
    try (Git git = Git.open(source.toFile())) {
      git.checkout().setCreateBranch(true).setName("master").setStartPoint("main").call();
    }
    workflow(source, "master workflow");
    try (Git git = Git.open(source.toFile())) {
      git.checkout().setName("main").call();
    }
    poller.poll();
    assertNotNull(cursor(id, "main"));
    assertNotNull(cursor(id, "master"));
    assertNotNull(cursor(id, "feature"));

    set(Settings.Granularity.PER_COMMIT, Settings.Scope.MAIN_ONLY, true);
    poller.poll();
    assertNotNull(cursor(id, "main"), "main stays watched");
    assertNotNull(cursor(id, "master"), "master stays watched");
    assertNull(cursor(id, "feature"), "other branches are dropped");
  }

  @Test
  void queueFullStopsTheBranchAndTheNextTickQueuesTheRest() throws Exception {
    source = newRepository("full");
    workflow(source, "add workflow");
    Long id = project("full", source);
    poller.poll();
    String a = commit(source, "a.txt", "a", "one");
    String b = commit(source, "b.txt", "b", "two");
    String c = commit(source, "c.txt", "c", "three");
    runner.queueLimit = 1;

    assertEquals(1, poller.poll());
    assertEquals(List.of(a), revisions(id));
    assertEquals(a, cursor(id, "main"));

    finish(id); // the single queued build finishes
    runner.queueLimit = 100;
    assertEquals(2, poller.poll());
    assertEquals(List.of(a, b, c), revisions(id));
    assertEquals(c, cursor(id, "main"));
  }

  @Test
  void maxCommitsAdvancesOnlyToTheLastQueuedCommit() throws Exception {
    source = newRepository("cap");
    workflow(source, "add workflow");
    Long id = project("cap", source);
    poller.poll();
    String a = commit(source, "a.txt", "a", "one");
    String b = commit(source, "b.txt", "b", "two");
    String c = commit(source, "c.txt", "c", "three");
    poller.maxCommits = 2;

    assertEquals(2, poller.poll());
    assertEquals(List.of(a, b), revisions(id));
    assertEquals(b, cursor(id, "main"));

    poller.maxCommits = 1000;
    assertEquals(1, poller.poll());
    assertEquals(List.of(a, b, c), revisions(id));
    assertEquals(c, cursor(id, "main"));
  }

  @Test
  void aSecondPollDoesNotRequeue() throws Exception {
    source = newRepository("restart");
    workflow(source, "add workflow");
    Long id = project("restart", source);
    poller.poll();
    commit(source, "a.txt", "a", "one");

    assertEquals(1, poller.poll());
    assertEquals(0, poller.poll());
    assertEquals(1, builds(id));
  }

  @Test
  void theSameCommitOnTwoBranchesBuildsOnceAndBothCursorsAdvance() throws Exception {
    source = newRepository("shared");
    String base = workflow(source, "add workflow");
    Long id = project("shared", source);
    poller.poll();
    try (Git git = Git.open(source.toFile())) {
      git.branchCreate().setName("dev").setStartPoint("main").call();
    }
    poller.poll();
    assertEquals(base, cursor(id, "main"));
    assertEquals(base, cursor(id, "dev"));

    String next = commit(source, "a.txt", "a", "one");
    try (Git git = Git.open(source.toFile())) {
      git.branchCreate().setName("dev").setForce(true).setStartPoint("main").call();
    }

    assertEquals(1, poller.poll());
    assertEquals(1, builds(id));
    assertEquals(next, cursor(id, "main"));
    assertEquals(next, cursor(id, "dev"));
  }

  @Test
  void privateProjectWithoutCredentialIsSkipped() throws Exception {
    source = newRepository("private");
    workflow(source, "add workflow");
    Long id = project("private", source.toUri().toString(), true, null);
    assertEquals(0, poller.poll());
    assertNull(cursor(id, "main"));
  }

  @Test
  void oneFailingProjectDoesNotStopTheOthers() throws Exception {
    Long bad = project("bad", "file:///definitely/not/a/repository.git", false, null);
    source = newRepository("good");
    workflow(source, "add workflow");
    Long good = project("good", source);

    assertEquals(0, poller.poll()); // seeds the good project, the bad one fails quietly
    commit(source, "a.txt", "a", "one");
    assertEquals(
        1, poller.poll()); // the bad project is still polled but does not stop the good one
    assertEquals(1, builds(good));
    assertEquals(0, builds(bad));
  }

  @Test
  void aQueuedBuildStillFindsItsCommitAfterItsBranchIsDeleted() throws Exception {
    source = newRepository("gone");
    workflow(source, "add workflow");
    Long id = project("gone", source);
    poller.poll();
    String queued = commit(source, "a.txt", "a", "one");
    poller.poll();
    assertEquals(1, builds(id));

    // Move the branch on, then delete it: the queued commit is no longer any ref's tip.
    commit(source, "b.txt", "b", "later");
    try (Git git = Git.open(source.toFile())) {
      git.checkout().setCreateBranch(true).setName("tmp").call();
      git.branchDelete().setBranchNames("main").setForce(true).call();
    }
    poller.poll(); // the mirror fetches with prune and drops refs/heads/main

    Path workspace = root.resolve("gone-workspace");
    String got =
        Checkout.fetch(
            workspace,
            mirrors.path(id).toUri().toString(),
            "main",
            null,
            true,
            1,
            100,
            queued,
            () -> false);
    assertEquals(queued, got);
    assertEquals("a", Files.readString(workspace.resolve("a.txt")));
  }

  @Test
  void aPollBuildPreparesItsWorkspaceFromTheMirrorAsAFileUri() throws Exception {
    source = newRepository("prep");
    workflow(source, "add workflow");
    Long id = project("prep", source);
    poller.poll();
    String queued = commit(source, "a.txt", "a", "one");
    poller.poll();
    assertEquals(1, builds(id));

    Path workspace = root.resolve("prep-workspace");
    String got =
        runner.prepareWorkspace(
            workspace,
            source.toUri().toString(),
            "main",
            null,
            queued,
            mirrors.path(id),
            1,
            100,
            () -> false);
    assertEquals(queued, got);
    assertEquals("a", Files.readString(workspace.resolve("a.txt")));
    try (Git git = Git.open(workspace.toFile())) {
      String origin = git.getRepository().getConfig().getString("remote", "origin", "url");
      assertTrue(
          origin.startsWith("file:"), "the mirror must be passed as a file: URI, was " + origin);
    }
  }

  @Test
  void aPollBuildFallsBackToTheRepositoryWhenItsMirrorIsGone() throws Exception {
    source = newRepository("fallback");
    workflow(source, "add workflow");
    Long id = project("fallback", source);
    poller.poll();
    String queued = commit(source, "a.txt", "a", "one");
    poller.poll();
    assertEquals(1, builds(id));

    // The queued poll build's mirror disappears before the runner starts it.
    mirrors.delete(id);
    assertFalse(Files.isDirectory(mirrors.path(id)), "the mirror must be gone for this test");

    Path workspace = root.resolve("fallback-workspace");
    // The test project URL is file: because the test profile allows local repositories. The
    // fallback must still take the normal URL path, which rejects file: instead of using it as a
    // local source. That proves the missing mirror did not win.
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runner.prepareWorkspace(
                    workspace,
                    source.toUri().toString(),
                    "main",
                    null,
                    queued,
                    mirrors.path(id),
                    1,
                    100,
                    () -> false));
    assertEquals(RepositoryUrls.SHAPE, failure.getMessage());
  }

  void mirrorManualBuilds(boolean on) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Settings s = Settings.findById(1L);
          if (s == null) {
            s = new Settings();
            s.persist();
          }
          s.mirrorManualBuilds = on;
        });
  }

  static Build manual(Long projectId) {
    Build b = new Build();
    b.projectId = projectId;
    b.trigger = Build.Trigger.MANUAL;
    return b;
  }

  @Test
  void aManualBuildFetchesTheMirrorAndChecksOutTheCurrentTipFromIt() throws Exception {
    source = newRepository("manual");
    workflow(source, "add workflow");
    Long id = project("manual", source);
    poller.poll();
    // Committed after the last poll: only a fetch at build time puts it in the mirror.
    String tip = commit(source, "b.txt", "b", "newer");
    mirrorManualBuilds(true);

    Path mirror = runner.mirrorFor(manual(id));
    assertEquals(mirrors.path(id), mirror);
    Path workspace = root.resolve("manual-workspace");
    String got =
        runner.prepareWorkspace(
            workspace, source.toUri().toString(), "main", null, null, mirror, 1, 100, () -> false);
    assertEquals(tip, got);
    assertEquals("b", Files.readString(workspace.resolve("b.txt")));
    try (Git git = Git.open(workspace.toFile())) {
      String origin = git.getRepository().getConfig().getString("remote", "origin", "url");
      assertTrue(origin.startsWith("file:"), "the mirror must be the source, was " + origin);
    }
  }

  @Test
  void aManualBuildClonesFromTheRepositoryWhenMirroringIsOff() throws Exception {
    source = newRepository("manual-off");
    workflow(source, "add workflow");
    Long id = project("manual-off", source);
    poller.poll();
    assertNull(runner.mirrorFor(manual(id)));
  }

  @Test
  void aManualBuildFallsBackToTheRepositoryWhenTheMirrorCannotBeFetched() throws Exception {
    source = newRepository("manual-gone");
    workflow(source, "add workflow");
    Long id = project("manual-gone", source);
    poller.poll();
    mirrorManualBuilds(true);
    // The repository disappears, so the fetch at build time fails.
    org.eclipse.jgit.util.FileUtils.delete(
        source.toFile(), org.eclipse.jgit.util.FileUtils.RECURSIVE);
    assertNull(runner.mirrorFor(manual(id)));
  }

  @Test
  void mirroredCommitSurvivesLaterPollsWhileABuildIsQueued() throws Exception {
    source = newRepository("survive");
    workflow(source, "add workflow");
    Long id = project("survive", source);
    poller.poll();
    String first = commit(source, "a.txt", "a", "one");
    poller.poll();
    assertEquals(1, builds(id));

    // The queued build reads revision `first` from the mirror at run time.
    commit(source, "b.txt", "b", "two");
    poller.poll();
    assertTrue(mirrors.has(id, first), "the first commit is still readable");
  }

  @Test
  void disablingDeletesMirrorAndCursorsAndReenablingSeeds() throws Exception {
    source = newRepository("disable");
    String tip = workflow(source, "add workflow");
    Long id = project("disable", source);
    poller.poll();
    assertTrue(Files.isDirectory(mirrors.path(id)));

    set(Settings.Granularity.PER_COMMIT, Settings.Scope.WORKFLOW, false);
    assertEquals(0, poller.poll());
    assertFalse(Files.isDirectory(mirrors.path(id)));
    assertNull(cursor(id, "main"));

    commit(source, "a.txt", "a", "missed while off");
    set(Settings.Granularity.PER_COMMIT, Settings.Scope.WORKFLOW, true);
    assertEquals(0, poller.poll()); // reseeds, nothing built for what happened while off
    assertEquals(0, builds(id));
    assertNotNull(cursor(id, "main"));
    assertNotEquals(tip, cursor(id, "main"));
  }

  @Test
  void disablingKeepsTheMirrorWhileAPollBuildIsQueued() throws Exception {
    source = newRepository("keepmirror");
    workflow(source, "add workflow");
    Long id = project("keepmirror", source);
    poller.poll();
    commit(source, "a.txt", "a", "one");
    poller.poll();
    assertEquals(1, builds(id));

    set(Settings.Granularity.PER_COMMIT, Settings.Scope.WORKFLOW, false);
    poller.poll();
    assertTrue(Files.isDirectory(mirrors.path(id)));
    assertNotNull(cursor(id, "main"));

    finish(id);
    poller.poll();
    assertFalse(Files.isDirectory(mirrors.path(id)));
  }

  @Test
  void mirrorOverItsSizeLimitStopsTheProjectWithoutDeletingIt() throws Exception {
    source = newRepository("large");
    workflow(source, "add workflow");
    Long id = project("large", source);
    poller.poll();
    byte[] big = new byte[2 * 1024 * 1024];
    new Random(42).nextBytes(big);
    String tip = commit(source, "big.bin", big, "big file");
    mirrors.maxMib = 1;

    assertEquals(0, poller.poll());
    assertEquals(0, builds(id));
    assertTrue(Files.isDirectory(mirrors.path(id)), "an oversized mirror is not deleted");

    // Cleanup while polling is off removes it and clears the stop.
    set(Settings.Granularity.PER_COMMIT, Settings.Scope.WORKFLOW, false);
    poller.poll();
    assertFalse(Files.isDirectory(mirrors.path(id)));
    assertNotEquals(tip, cursor(id, "main"));
  }

  @Test
  void overlappingTicksDoNotDuplicateBuilds() throws Exception {
    source = newRepository("overlap");
    workflow(source, "add workflow");
    Long id = project("overlap", source);
    poller.poll();
    String a = commit(source, "a.txt", "a", "one");
    String b = commit(source, "b.txt", "b", "two");
    String c = commit(source, "c.txt", "c", "three");

    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      CountDownLatch start = new CountDownLatch(1);
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
      first.get(30, TimeUnit.SECONDS);
      second.get(30, TimeUnit.SECONDS);
    }
    assertEquals(3, builds(id));
    assertEquals(Set.copyOf(List.of(a, b, c)), new TreeSet<>(revisions(id)));
  }

  @Test
  void mergeCommitsQueueDeterministicallyOldestFirst() throws Exception {
    source = newRepository("mergeorder");
    workflow(source, "add workflow");
    Long id = project("mergeorder", source);
    poller.poll(); // seed the workflow commit

    try (Git git = Git.open(source.toFile())) {
      git.checkout().setCreateBranch(true).setName("side").call();
    }
    String side = commitAt(source, "side.txt", "s", "side", 1_700_000_200L);
    try (Git git = Git.open(source.toFile())) {
      git.checkout().setName("main").call();
    }
    String main = commitAt(source, "main.txt", "m", "main", 1_700_000_100L);
    String merge;
    try (Git git = Git.open(source.toFile())) {
      git.checkout().setName("main").call();
      git.merge()
          .include(git.getRepository().resolve("refs/heads/side"))
          .setFastForward(MergeCommand.FastForwardMode.NO_FF)
          .setCommit(false)
          .call();
      merge = commitAt(source, "merge.txt", "x", "merge", 1_700_000_300L);
    }

    assertEquals(3, poller.poll());
    assertEquals(
        List.of(main, side, merge),
        revisions(id),
        "parents are built before the merge commit, oldest first");
    assertEquals(merge, cursor(id, "main"));
  }

  @Test
  void aBranchDeletedAndRecreatedIsSeededAgainAndBuildsOnlyNewCommits() throws Exception {
    source = newRepository("recreated");
    workflow(source, "add workflow");
    Long id = project("recreated", source);
    poller.poll();
    String before = commit(source, "a.txt", "a", "one");
    assertEquals(1, poller.poll());
    assertEquals(before, cursor(id, "main"));

    try (Git git = Git.open(source.toFile())) {
      git.checkout().setCreateBranch(true).setName("tmp").call();
      git.branchDelete().setBranchNames("main").setForce(true).call();
    }
    assertEquals(0, poller.poll());
    assertNull(cursor(id, "main"));

    try (Git git = Git.open(source.toFile())) {
      git.checkout().setCreateBranch(true).setName("main").setStartPoint("tmp").call();
    }
    assertEquals(0, poller.poll(), "the recreated branch is a fresh first sighting");
    assertEquals(before, cursor(id, "main"));
    assertEquals(1, builds(id), "history before the deletion is not rebuilt");

    String after = commit(source, "b.txt", "b", "two");
    assertEquals(1, poller.poll());
    assertEquals(List.of(before, after), revisions(id));
    assertEquals(after, cursor(id, "main"));
  }

  @Test
  void queueFullIsToldApartFromAGenericFailure() {
    assertTrue(
        CommitPoller.queueFull(new WebApplicationException(Response.status(429).build())),
        "429 is the queue-full signal");
    assertFalse(
        CommitPoller.queueFull(new WebApplicationException(Response.status(500).build())),
        "any other status is a real failure");
    assertFalse(
        CommitPoller.queueFull(new WebApplicationException("no response")),
        "an exception without a response is a real failure");
  }

  @Test
  void mirrorDirectoryUnderTheTemporaryDirectoryIsRejected() {
    String tmp = System.getProperty("java.io.tmpdir");
    assertThrows(
        IllegalStateException.class,
        () -> RepositoryMirror.validateMirrorDirectory(Path.of(tmp, "m").toString(), tmp));
    assertDoesNotThrow(() -> RepositoryMirror.validateMirrorDirectory("target/mirrors", tmp));
  }

  @Test
  void mirrorsAreOwnerOnlyWhereTheFilesystemSupportsIt() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    source = newRepository("perms");
    workflow(source, "add workflow");
    Long id = project("perms", source);
    poller.poll();

    assertEquals(
        "rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(mirrors.root())));
    assertEquals(
        "rwx------",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(mirrors.path(id))));
  }

  @Test
  void mirrorsOfDeletedProjectsAreSweptOnEveryTick() throws Exception {
    source = newRepository("sweep");
    workflow(source, "add workflow");
    Long id = project("sweep", source);
    poller.poll();
    assertTrue(Files.isDirectory(mirrors.path(id)));

    Path orphan = mirrors.root().resolve("424242.git");
    Files.createDirectories(orphan);

    poller.poll();
    assertFalse(Files.exists(orphan), "an orphan mirror is removed even while polling is on");
    assertTrue(Files.isDirectory(mirrors.path(id)), "a live mirror is kept");
  }

  @Test
  void deletingAMirrorDoesNotDropAHeldLock() {
    Long id = 999999L;
    ReentrantLock lock = mirrors.lock(id);
    lock.lock();
    try {
      mirrors.delete(id);
      assertSame(lock, mirrors.lock(id), "the held lock must stay in the map");
    } finally {
      lock.unlock();
    }
  }

  @Test
  void localSourceModeIsNotReachableFromRequestInput() {
    // A build request has only a branch and a credential. Revision and trigger are internal.
    var components = BuildResource.RunRequest.class.getRecordComponents();
    assertEquals(2, components.length);
    for (var component : components) {
      assertFalse(
          component.getName().equals("revision") || component.getName().equals("trigger"),
          component.getName() + " must not be request input");
    }
  }

  @Test
  void disablingPollingKeepsMirrorsWhenMirrorManualBuildsIsEnabled() throws Exception {
    source = newRepository("keep-manual-mirror");
    workflow(source, "add workflow");
    Long id = project("keep-manual-mirror", source);
    poller.poll();
    assertTrue(Files.isDirectory(mirrors.path(id)));
    assertNotNull(cursor(id, "main"));

    set(Settings.Granularity.PER_COMMIT, Settings.Scope.WORKFLOW, false);
    mirrorManualBuilds(true);

    poller.poll();
    assertTrue(
        Files.isDirectory(mirrors.path(id)),
        "live project mirror must be kept when mirrorManualBuilds is enabled");
    assertNull(cursor(id, "main"), "branch cursors must still reset when polling is off");
  }

  @Test
  void cleanUpDisabledKeepsMirrorWhileManualBuildIsRunning() throws Exception {
    source = newRepository("active-manual-mirror");
    workflow(source, "add workflow");
    Long id = project("active-manual-mirror", source);
    set(Settings.Granularity.PER_COMMIT, Settings.Scope.WORKFLOW, false);
    mirrorManualBuilds(true);
    Path mirror = runner.mirrorFor(manual(id));
    assertTrue(Files.isDirectory(mirror));

    Long buildId =
        QuarkusTransaction.requiringNew()
            .call(() -> {
              Build b = new Build();
              b.projectId = id;
              b.number = Build.nextNumber(id);
              b.requestedBy = "owner";
              b.trigger = Build.Trigger.MANUAL;
              b.status = Build.Status.RUNNING;
              b.branch = "main";
              b.requestedAt = Instant.now();
              b.startedAt = Instant.now();
              b.persist();
              return b.id;
            });

    mirrorManualBuilds(false);
    poller.poll();
    assertTrue(
        Files.isDirectory(mirrors.path(id)),
        "mirror must not be deleted while an active manual build is running");

    QuarkusTransaction.requiringNew()
        .run(() -> {
          Build b = Build.findById(buildId);
          b.status = Build.Status.PASSED;
          b.finishedAt = Instant.now();
        });
    poller.poll();
    assertFalse(
        Files.exists(mirrors.path(id)),
        "mirror should be deleted once the manual build finishes and mirroring is off");
  }
}
