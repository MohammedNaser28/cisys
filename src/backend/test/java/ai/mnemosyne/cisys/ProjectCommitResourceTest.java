/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The commit view against real local repositories and bare mirrors. */
@QuarkusTest
class ProjectCommitResourceTest {
  @Inject RepositoryMirror mirrors;
  @Inject CredentialCipher cipher;

  @TempDir Path root;
  Path source;

  @BeforeEach
  void setUp() {
    mirrors = ClientProxy.unwrap(mirrors);
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Build.deleteAll();
          ProjectBranch.deleteAll();
          Project.deleteAll();
          Settings.deleteAll();
        });
    mirrors.deleteAll();
    mirrors.readFailure = (op, detail) -> false;
  }

  @AfterEach
  void tearDown() {
    mirrors.readFailure = (op, detail) -> false;
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Build.deleteAll();
          ProjectBranch.deleteAll();
          Project.deleteAll();
        });
    mirrors.deleteAll();
  }

  // --- fixtures --------------------------------------------------------------------------------

  private Path newRepository(String name) throws Exception {
    Path dir = Files.createDirectory(root.resolve(name));
    try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
      return dir;
    }
  }

  private String commitAt(Path repo, String path, String content, String message, long epochSeconds)
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

  private List<String> commitChain(Path repo, int count) throws Exception {
    List<String> newestFirst = new ArrayList<>();
    try (Git git = Git.open(repo.toFile())) {
      var repository = git.getRepository();
      try (ObjectInserter inserter = repository.newObjectInserter()) {
        ObjectId tree = inserter.insert(Constants.OBJ_TREE, new byte[0]);
        ObjectId parent = null;
        for (int i = 0; i < count; i++) {
          PersonIdent who =
              new PersonIdent(
                  "tester",
                  "t@example.com",
                  Instant.ofEpochSecond(1_700_000_000L + i),
                  ZoneOffset.UTC);
          CommitBuilder commit = new CommitBuilder();
          commit.setTreeId(tree);
          if (parent != null) {
            commit.setParentId(parent);
          }
          commit.setAuthor(who);
          commit.setCommitter(who);
          commit.setMessage("commit " + i);
          parent = inserter.insert(commit);
          newestFirst.add(parent.name());
        }
        inserter.flush();
        RefUpdate update = repository.updateRef("refs/heads/main");
        update.setNewObjectId(parent);
        update.setForceUpdate(true);
        update.update();
      }
    }
    Collections.reverse(newestFirst);
    return newestFirst;
  }

  private void branch(Path repo, String name) throws Exception {
    try (Git git = Git.open(repo.toFile())) {
      git.checkout().setCreateBranch(true).setName(name).call();
    }
  }

  private void checkout(Path repo, String name) throws Exception {
    try (Git git = Git.open(repo.toFile())) {
      git.checkout().setName(name).call();
    }
  }

  private record Seeded(Long id, String name) {}

  private Seeded project(String name, Path repo, boolean privateRepository, String credentialRef) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project p = new Project();
          p.name = name;
          p.repositoryUrl = repo.toUri().toString();
          p.ownerSubject = "owner-id";
          p.privateRepository = privateRepository;
          p.credentialRef = credentialRef;
          p.persist();
          ProjectMember m = new ProjectMember();
          m.projectId = p.id;
          m.subject = p.ownerSubject;
          m.role = ProjectMember.TeamRole.LEAD;
          m.createdAt = Instant.now();
          m.persist();
          return new Seeded(p.id, p.name);
        });
  }

  private Seeded publicProject(Path repo) {
    return project("Commits-" + UUID.randomUUID(), repo, false, null);
  }

  private Seeded teamTokenProject(Path repo) {
    Seeded seeded = project("TeamToken-" + UUID.randomUUID(), repo, true, null);
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Team team = new Team();
          team.name = "commit-team-" + UUID.randomUUID();
          team.ownerSubject = "owner-id";
          team.createdAt = Instant.now();
          team.persistAndFlush();
          team.tokenSecret = cipher.encrypt("team-token", team.id);
          Project.<Project>findById(seeded.id()).teamId = team.id;
        });
    return seeded;
  }

  private void mirror(Seeded project) throws Exception {
    Project p = QuarkusTransaction.requiringNew().call(() -> Project.findById(project.id()));
    ReentrantLock lock = mirrors.lock(project.id());
    lock.lock();
    try {
      mirrors.fetch(p);
    } finally {
      lock.unlock();
    }
  }

  private Long build(
      Long projectId, String revision, String branch, Build.Status status, Build.Trigger trigger) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Build b = new Build();
          b.projectId = projectId;
          b.number = Build.nextNumber(projectId);
          b.revision = revision;
          b.branch = branch;
          b.status = status;
          b.trigger = trigger;
          b.persist();
          return b.id;
        });
  }

  private static String path(String name) {
    return "/api/project-pages/" + name + "/commits";
  }

  private static List<String> revisions(String name, String branch, int skip, int limit) {
    return given()
        .queryParam("branch", branch)
        .queryParam("skip", skip)
        .queryParam("limit", limit)
        .get(path(name))
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("commits.revision");
  }

  // --- mirror history --------------------------------------------------------------------------

  @Test
  @TestSecurity(user = "owner", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
  void privateTeamTokenProjectUsesItsConfiguredMirror() throws Exception {
    source = newRepository("team-token");
    String revision = commitAt(source, "README", "team", "team", 1_700_000_100L);
    Seeded project = teamTokenProject(source);
    mirror(project);

    given()
        .queryParam("branch", "main")
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits.revision", contains(revision));
  }

  @Test
  void anonymousReadsMirrorCommitsNewestFirst() throws Exception {
    source = newRepository("order");
    String first = commitAt(source, "a.txt", "a", "one", 1_700_000_100L);
    String second = commitAt(source, "b.txt", "b", "two", 1_700_000_200L);
    String third = commitAt(source, "c.txt", "c", "three", 1_700_000_300L);
    Seeded project = publicProject(source);
    mirror(project);

    given()
        .queryParam("branch", "main")
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits.revision", contains(third, second, first))
        .body("commits[0].message", equalTo("three"))
        .body("commits[0].author", equalTo("tester"))
        .body("commits[0].authoredAt", notNullValue())
        .body("commits[0].builds", hasSize(0))
        .body("hasMore", equalTo(false))
        .body("reason", nullValue());
    assertFalse(mirrors.lock(project.id()).isLocked(), "the mirror lock is released");
  }

  @Test
  void mergeCommitsAreIncludedAndEqualTimestampsAreDeterministic() throws Exception {
    source = newRepository("merge");
    String base = commitAt(source, "base.txt", "base", "base", 1_700_000_100L);
    branch(source, "side");
    String side = commitAt(source, "side.txt", "s", "side", 1_700_000_200L);
    checkout(source, "main");
    String main = commitAt(source, "main.txt", "m", "main", 1_700_000_200L);
    String merge;
    try (Git git = Git.open(source.toFile())) {
      git.merge()
          .include(git.getRepository().resolve("refs/heads/side"))
          .setFastForward(MergeCommand.FastForwardMode.NO_FF)
          .setCommit(false)
          .call();
    }
    merge = commitAt(source, "merge.txt", "x", "merge", 1_700_000_300L);
    Seeded project = publicProject(source);
    mirror(project);

    List<String> first = revisions(project.name(), "main", 0, 50);
    List<String> second = revisions(project.name(), "main", 0, 50);
    assertEquals(first, second, "the same repository state has the same order");
    assertEquals(Set.of(base, side, main, merge), new HashSet<>(first));
    assertEquals(merge, first.get(0), "the newest commit is first");
  }

  @Test
  void branchFilterUsesOnlyThatBranch() throws Exception {
    source = newRepository("branches");
    String main = commitAt(source, "main.txt", "m", "main", 1_700_000_100L);
    branch(source, "dev");
    String dev = commitAt(source, "dev.txt", "d", "dev", 1_700_000_200L);
    Seeded project = publicProject(source);
    mirror(project);

    given()
        .queryParam("branch", "dev")
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits.revision", contains(dev, main));
    given()
        .queryParam("branch", "main")
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits.revision", contains(main));
  }

  @Test
  void paginationUsesLimitAndHasMore() throws Exception {
    source = newRepository("paging");
    String first = commitAt(source, "a.txt", "a", "one", 1_700_000_100L);
    String second = commitAt(source, "b.txt", "b", "two", 1_700_000_200L);
    String third = commitAt(source, "c.txt", "c", "three", 1_700_000_300L);
    String fourth = commitAt(source, "d.txt", "d", "four", 1_700_000_400L);
    String fifth = commitAt(source, "e.txt", "e", "five", 1_700_000_500L);
    Seeded project = publicProject(source);
    mirror(project);

    given()
        .queryParam("branch", "main")
        .queryParam("limit", 2)
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits.revision", contains(fifth, fourth))
        .body("hasMore", equalTo(true));
    given()
        .queryParam("branch", "main")
        .queryParam("skip", 2)
        .queryParam("limit", 2)
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits.revision", contains(third, second))
        .body("hasMore", equalTo(true));
    given()
        .queryParam("branch", "main")
        .queryParam("skip", 4)
        .queryParam("limit", 2)
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits.revision", contains(first))
        .body("hasMore", equalTo(false));
  }

  @Test
  void defaultLimitIsFifty() throws Exception {
    source = newRepository("default-limit");
    for (int i = 0; i < 51; i++) {
      commitAt(source, "file.txt", "value " + i, "commit " + i, 1_700_000_000L + i);
    }
    Seeded project = publicProject(source);
    mirror(project);

    given()
        .queryParam("branch", "main")
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits", hasSize(50))
        .body("hasMore", equalTo(true));
    given()
        .queryParam("branch", "main")
        .queryParam("skip", 50)
        .queryParam("limit", 50)
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits", hasSize(1))
        .body("hasMore", equalTo(false));
  }

  @Test
  void shortHistoryHasNoHistoryLimitAtTheBoundary() throws Exception {
    source = newRepository("short-boundary");
    String first = commitAt(source, "a.txt", "a", "one", 1_700_000_100L);
    commitAt(source, "b.txt", "b", "two", 1_700_000_200L);
    commitAt(source, "c.txt", "c", "three", 1_700_000_300L);
    Seeded project = publicProject(source);
    mirror(project);

    given()
        .queryParam("branch", "main")
        .queryParam("skip", 2)
        .queryParam("limit", 50)
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits.revision", contains(first))
        .body("hasMore", equalTo(false))
        .body("reason", nullValue());
    given()
        .queryParam("branch", "main")
        .queryParam("skip", ProjectCommitResource.MAX_COMMITS - 50)
        .queryParam("limit", 50)
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits", hasSize(0))
        .body("hasMore", equalTo(false))
        .body("reason", nullValue());
    given()
        .queryParam("branch", "main")
        .queryParam("skip", ProjectCommitResource.MAX_COMMITS)
        .queryParam("limit", 50)
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits", hasSize(0))
        .body("hasMore", equalTo(false))
        .body("reason", nullValue());
  }

  @Test
  void exactlyMaxCommitsHasNoHistoryLimit() throws Exception {
    source = newRepository("max-exact");
    int boundary = ProjectCommitResource.MAX_COMMITS;
    List<String> newestFirst = commitChain(source, boundary);
    Seeded project = publicProject(source);
    mirror(project);

    given()
        .queryParam("branch", "main")
        .queryParam("skip", 0)
        .queryParam("limit", 50)
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits", hasSize(50))
        .body("hasMore", equalTo(true))
        .body("reason", nullValue());
    given()
        .queryParam("branch", "main")
        .queryParam("skip", boundary - 50)
        .queryParam("limit", 50)
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body(
            "commits.revision",
            contains(newestFirst.subList(boundary - 50, boundary).toArray(String[]::new)))
        .body("hasMore", equalTo(false))
        .body("reason", nullValue());
    given()
        .queryParam("branch", "main")
        .queryParam("skip", boundary - 1)
        .queryParam("limit", 50)
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits.revision", contains(newestFirst.get(boundary - 1)))
        .body("hasMore", equalTo(false))
        .body("reason", nullValue());
  }

  @Test
  void moreThanMaxCommitsReportsHistoryLimitOnlyAtTheBoundary() throws Exception {
    source = newRepository("max-more");
    int boundary = ProjectCommitResource.MAX_COMMITS;
    List<String> newestFirst = commitChain(source, boundary + 1);
    Seeded project = publicProject(source);
    mirror(project);

    given()
        .queryParam("branch", "main")
        .queryParam("skip", 0)
        .queryParam("limit", 50)
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits", hasSize(50))
        .body("hasMore", equalTo(true))
        .body("reason", nullValue());
    given()
        .queryParam("branch", "main")
        .queryParam("skip", boundary - 50)
        .queryParam("limit", 50)
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body(
            "commits.revision",
            contains(newestFirst.subList(boundary - 50, boundary).toArray(String[]::new)))
        .body("hasMore", equalTo(false))
        .body("reason", equalTo(ProjectCommitResource.HISTORY_LIMIT));
    given()
        .queryParam("branch", "main")
        .queryParam("skip", boundary - 1)
        .queryParam("limit", 50)
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits.revision", contains(newestFirst.get(boundary - 1)))
        .body("hasMore", equalTo(false))
        .body("reason", equalTo(ProjectCommitResource.HISTORY_LIMIT));
    given()
        .queryParam("branch", "main")
        .queryParam("skip", boundary)
        .queryParam("limit", 50)
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits", hasSize(0))
        .body("hasMore", equalTo(false))
        .body("reason", equalTo(ProjectCommitResource.HISTORY_LIMIT));
  }

  @Test
  void maxCommitsAndBadParameters() throws Exception {
    source = newRepository("bounds");
    commitAt(source, "a.txt", "a", "one", 1_700_000_100L);
    Seeded project = publicProject(source);
    mirror(project);

    given()
        .queryParam("branch", "main")
        .queryParam("skip", ProjectCommitResource.MAX_COMMITS + 1)
        .get(path(project.name()))
        .then()
        .statusCode(400);
    given()
        .queryParam("branch", "main")
        .queryParam("skip", -1)
        .get(path(project.name()))
        .then()
        .statusCode(400);
    given()
        .queryParam("branch", "main")
        .queryParam("limit", 0)
        .get(path(project.name()))
        .then()
        .statusCode(400);
    given()
        .queryParam("branch", "main")
        .queryParam("limit", 101)
        .get(path(project.name()))
        .then()
        .statusCode(400);
    given()
        .queryParam("branch", "main")
        .queryParam("limit", 100)
        .get(path(project.name()))
        .then()
        .statusCode(200);
    given().get(path(project.name())).then().statusCode(400);
    given().queryParam("branch", "  ").get(path(project.name())).then().statusCode(400);
  }

  @Test
  void branchWithSlashIsAccepted() throws Exception {
    source = newRepository("slash-branch");
    String main = commitAt(source, "main.txt", "m", "main", 1_700_000_100L);
    branch(source, "feature/x");
    String feature = commitAt(source, "feature.txt", "f", "feature", 1_700_000_200L);
    Seeded project = publicProject(source);
    mirror(project);

    given()
        .queryParam("branch", "feature/x")
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits.revision", contains(feature, main));
  }

  @Test
  void invalidBranchSyntaxIsBadRequestAndAccessIsCheckedFirst() throws Exception {
    source = newRepository("invalid-branch");
    commitAt(source, "a.txt", "a", "one", 1_700_000_100L);
    Seeded project = publicProject(source);
    mirror(project);

    for (String branch : List.of("bad..branch", "bad~branch", "bad branch", "bad:branch")) {
      given().queryParam("branch", branch).get(path(project.name())).then().statusCode(400);
    }

    Path privateSource = newRepository("invalid-branch-private");
    Seeded privateProject = project("Commits-" + UUID.randomUUID(), privateSource, true, null);
    given()
        .queryParam("branch", "bad..branch")
        .get(path(privateProject.name()))
        .then()
        .statusCode(404);
  }

  @Test
  void buildsIncludeOtherBranchesAndCommitsWithoutBuilds() throws Exception {
    source = newRepository("builds");
    String first = commitAt(source, "a.txt", "a", "one", 1_700_000_100L);
    String second = commitAt(source, "b.txt", "b", "two", 1_700_000_200L);
    Seeded project = publicProject(source);
    mirror(project);

    build(project.id(), first, "main", Build.Status.PASSED, Build.Trigger.MANUAL);
    build(project.id(), first, "dev", Build.Status.FAILED, Build.Trigger.POLL);
    build(project.id(), first, "main", Build.Status.PASSED, Build.Trigger.MANUAL);

    given()
        .queryParam("branch", "main")
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits.revision", contains(second, first))
        .body("commits[0].builds", hasSize(0))
        .body("commits[1].builds", hasSize(3))
        .body("commits[1].builds.branch", hasItem("dev"))
        .body("commits[1].builds[0].number", equalTo(3))
        .body("commits[1].builds[0].status", equalTo("PASSED"))
        .body("commits[1].builds[0].trigger", equalTo("MANUAL"));
  }

  // --- fallback --------------------------------------------------------------------------------

  @Test
  void missingMirrorFallsBackToBuilds() throws Exception {
    source = newRepository("no-mirror");
    String first = commitAt(source, "a.txt", "a", "one", 1_700_000_100L);
    String second = commitAt(source, "b.txt", "b", "two", 1_700_000_200L);
    Seeded project = publicProject(source);
    build(project.id(), first, "main", Build.Status.PASSED, Build.Trigger.MANUAL);
    build(project.id(), second, "main", Build.Status.PASSED, Build.Trigger.MANUAL);

    given()
        .queryParam("branch", "main")
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits.revision", contains(second, first))
        .body("commits[0].message", nullValue())
        .body("commits[0].author", nullValue())
        .body("commits[0].authoredAt", nullValue())
        .body("commits[0].builds", hasSize(1))
        .body("hasMore", equalTo(false))
        .body("reason", equalTo(ProjectCommitResource.HISTORY_UNAVAILABLE));
    given()
        .queryParam("branch", "main")
        .queryParam("limit", 1)
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits", hasSize(1))
        .body("hasMore", equalTo(true));
    given()
        .queryParam("branch", "main")
        .queryParam("skip", 1)
        .queryParam("limit", 1)
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits", hasSize(1))
        .body("hasMore", equalTo(false));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
  void privateProjectWithoutCredentialUsesFallback() throws Exception {
    source = newRepository("private-no-credential");
    String commit = commitAt(source, "a.txt", "a", "one", 1_700_000_100L);
    Seeded project = project("Commits-" + UUID.randomUUID(), source, true, null);
    mirror(project);
    build(project.id(), commit, "main", Build.Status.PASSED, Build.Trigger.MANUAL);

    given()
        .queryParam("branch", "main")
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits.revision", contains(commit))
        .body("commits[0].message", nullValue())
        .body("reason", equalTo(ProjectCommitResource.HISTORY_UNAVAILABLE));
  }

  @Test
  void branchMissingFromMirrorFallsBack() throws Exception {
    source = newRepository("missing-branch");
    commitAt(source, "main.txt", "m", "main", 1_700_000_100L);
    Seeded project = publicProject(source);
    mirror(project);

    branch(source, "dev");
    String dev = commitAt(source, "dev.txt", "d", "dev", 1_700_000_200L);
    build(project.id(), dev, "dev", Build.Status.PASSED, Build.Trigger.MANUAL);

    given()
        .queryParam("branch", "dev")
        .get(path(project.name()))
        .then()
        .statusCode(200)
        .body("commits.revision", contains(dev))
        .body("commits[0].message", nullValue())
        .body("reason", equalTo(ProjectCommitResource.HISTORY_UNAVAILABLE));
  }

  // --- access, failures and locking ------------------------------------------------------------

  @Test
  void anonymousCannotSeePrivateMirrorCommitsEvenWhenTheLockIsBusy() throws Exception {
    source = newRepository("private-mirror");
    String commit = commitAt(source, "a.txt", "a", "one", 1_700_000_100L);
    Seeded project = project("Commits-" + UUID.randomUUID(), source, true, null);
    mirror(project);
    build(project.id(), commit, "main", Build.Status.PASSED, Build.Trigger.MANUAL);

    ReentrantLock lock = mirrors.lock(project.id());
    lock.lock();
    try {
      given().queryParam("branch", "main").get(path(project.name())).then().statusCode(404);
    } finally {
      lock.unlock();
    }
  }

  @Test
  void anonymousCannotSeePrivateFallbackCommits() throws Exception {
    Path noMirrorSource = newRepository("private-fallback-no-mirror");
    String noMirrorCommit = commitAt(noMirrorSource, "a.txt", "a", "one", 1_700_000_100L);
    Seeded noMirror = project("Commits-" + UUID.randomUUID(), noMirrorSource, true, null);
    build(noMirror.id(), noMirrorCommit, "main", Build.Status.PASSED, Build.Trigger.MANUAL);
    given().queryParam("branch", "main").get(path(noMirror.name())).then().statusCode(404);

    Path noCredentialSource = newRepository("private-fallback-no-credential");
    String noCredentialCommit = commitAt(noCredentialSource, "a.txt", "a", "one", 1_700_000_100L);
    Seeded noCredential = project("Commits-" + UUID.randomUUID(), noCredentialSource, true, null);
    mirror(noCredential);
    build(noCredential.id(), noCredentialCommit, "main", Build.Status.PASSED, Build.Trigger.MANUAL);
    given().queryParam("branch", "main").get(path(noCredential.name())).then().statusCode(404);

    Path missingBranchSource = newRepository("private-fallback-missing-branch");
    commitAt(missingBranchSource, "main.txt", "m", "main", 1_700_000_100L);
    Seeded missingBranch = project("Commits-" + UUID.randomUUID(), missingBranchSource, true, null);
    mirror(missingBranch);
    // The mirror was fetched while no credential was needed; the page still sees a credential.
    QuarkusTransaction.requiringNew()
        .run(() ->
            Project.update(
                "credentialRef = ?1 where id = ?2", "CISYS_GIT_TEST", missingBranch.id()));
    branch(missingBranchSource, "dev");
    String dev = commitAt(missingBranchSource, "dev.txt", "d", "dev", 1_700_000_200L);
    build(missingBranch.id(), dev, "dev", Build.Status.PASSED, Build.Trigger.MANUAL);
    given().queryParam("branch", "dev").get(path(missingBranch.name())).then().statusCode(404);
  }

  @Test
  void mirrorReadFailureIsBadGatewayAndReleasesTheLock() throws Exception {
    source = newRepository("read-failure");
    commitAt(source, "a.txt", "a", "one", 1_700_000_100L);
    Seeded project = publicProject(source);
    mirror(project);
    mirrors.readFailure = (op, detail) -> "commits".equals(op);
    try {
      given()
          .queryParam("branch", "main")
          .get(path(project.name()))
          .then()
          .statusCode(502)
          .body("error", equalTo("Unable to read commits from the repository mirror"));
    } finally {
      mirrors.readFailure = (op, detail) -> false;
    }
    assertFalse(mirrors.lock(project.id()).isLocked(), "the mirror lock is released after failure");
  }

  @Test
  void mirrorDeletedDuringReadFallsBackToBuildsAndReleasesTheLock() throws Exception {
    source = newRepository("mirror-vanished");
    String commit = commitAt(source, "a.txt", "a", "one", 1_700_000_100L);
    Seeded project = publicProject(source);
    mirror(project);
    build(project.id(), commit, "main", Build.Status.PASSED, Build.Trigger.MANUAL);
    mirrors.readFailure =
        (op, detail) -> {
          if ("commits".equals(op)) {
            mirrors.delete(project.id());
            return true;
          }
          return false;
        };
    try {
      given()
          .queryParam("branch", "main")
          .get(path(project.name()))
          .then()
          .statusCode(200)
          .body("commits.revision", contains(commit))
          .body("commits[0].message", nullValue())
          .body("hasMore", equalTo(false))
          .body("reason", equalTo(ProjectCommitResource.HISTORY_UNAVAILABLE));
    } finally {
      mirrors.readFailure = (op, detail) -> false;
    }
    assertFalse(
        mirrors.lock(project.id()).isLocked(), "the mirror lock is released after fallback");
  }

  @Test
  void busyMirrorReturnsServiceUnavailableWithRetryAfter() throws Exception {
    source = newRepository("busy");
    commitAt(source, "a.txt", "a", "one", 1_700_000_100L);
    Seeded project = publicProject(source);
    mirror(project);

    ReentrantLock lock = mirrors.lock(project.id());
    lock.lock();
    try {
      given()
          .queryParam("branch", "main")
          .get(path(project.name()))
          .then()
          .statusCode(503)
          .header("Retry-After", equalTo(String.valueOf(ProjectCommitResource.RETRY_AFTER_SECONDS)))
          .body("error", equalTo("The project's Git mirror is busy; try again"));
    } finally {
      lock.unlock();
    }
    assertFalse(mirrors.lock(project.id()).isLocked(), "the test releases the lock");
  }

  @Test
  void unknownProjectIsNotFound() {
    given()
        .queryParam("branch", "main")
        .get(path("does-not-exist-" + UUID.randomUUID()))
        .then()
        .statusCode(404);
  }
}
