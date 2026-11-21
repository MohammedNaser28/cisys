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
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;

@QuarkusTest
@TestProfile(TeamTestProfile.class)
@ResourceLock("audit-events")
@Execution(ExecutionMode.SAME_THREAD)
class AuditResourceTest {
  private static final String PATH = "/api/audit";
  private static final String JWT =
      "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";

  @BeforeEach
  void setUp() {
    removeFailingAuditTrigger();
    clean();
    TestUserDirectory.add("admin-id", "admin", "admin");
    TestUserDirectory.add("lead-id", "lead", "lead");
    TestUserDirectory.add("member-id", "member", "member");
  }

  @AfterEach
  void tearDown() {
    removeFailingAuditTrigger();
    clean();
    TestUserDirectory.reset();
  }

  private static void clean() {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          AuditEvent.deleteAll();
          AllowedImage.deleteAll();
          NetworkHost.deleteAll();
          Article.deleteAll();
          Build.deleteAll();
          ProjectMember.deleteAll();
          Project.deleteAll();
          TeamMember.deleteAll();
          Team.deleteAll();
          Settings.deleteAll();
        });
  }

  private static void installFailingAuditTrigger() {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Panache.getEntityManager()
              .createNativeQuery(
                  "CREATE OR REPLACE FUNCTION fail_audit_insert() RETURNS trigger AS $$ "
                      + "BEGIN RAISE EXCEPTION 'audit insert disabled for test'; END; "
                      + "$$ LANGUAGE plpgsql")
              .executeUpdate();
          Panache.getEntityManager()
              .createNativeQuery("DROP TRIGGER IF EXISTS fail_audit_insert ON auditevent")
              .executeUpdate();
          Panache.getEntityManager()
              .createNativeQuery(
                  "CREATE TRIGGER fail_audit_insert BEFORE INSERT ON auditevent "
                      + "FOR EACH ROW EXECUTE FUNCTION fail_audit_insert()")
              .executeUpdate();
        });
  }

  private static void removeFailingAuditTrigger() {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Panache.getEntityManager()
              .createNativeQuery("DROP TRIGGER IF EXISTS fail_audit_insert ON auditevent")
              .executeUpdate();
          Panache.getEntityManager()
              .createNativeQuery("DROP FUNCTION IF EXISTS fail_audit_insert()")
              .executeUpdate();
        });
  }

  private static Long event(
      String action,
      AuditOutcome outcome,
      String actorSubject,
      String actorUsername,
      Long projectId,
      String projectName,
      AuditTargetType targetType,
      String targetId,
      String targetName,
      String summary,
      Instant occurredAt,
      Map<String, Object> details) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          AuditEvent e = new AuditEvent();
          e.occurredAt = occurredAt;
          e.action = action;
          e.outcome = outcome;
          e.actorSubject = actorSubject;
          e.actorUsername = actorUsername;
          e.actorRoles = "admin";
          e.projectId = projectId;
          e.projectName = projectName;
          e.targetType = targetType;
          e.targetId = targetId;
          e.targetName = targetName;
          e.summary = summary;
          e.details = details;
          e.persist();
          return e.id;
        });
  }

  private static AuditEvent find(String action, AuditOutcome outcome) {
    return QuarkusTransaction.requiringNew()
        .call(() ->
            AuditEvent.<AuditEvent>find(
                    "action = ?1 and outcome = ?2 order by id desc", action, outcome)
                .firstResult());
  }

  private static long count(String action) {
    return QuarkusTransaction.requiringNew().call(() -> AuditEvent.count("action = ?1", action));
  }

  private static Long project(String owner) {
    return project(owner, "audit-" + UUID.randomUUID());
  }

  private static Long project(String owner, String name) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project p = new Project();
          String n = UUID.randomUUID().toString();
          p.name = name;
          p.repositoryUrl = "https://github.com/octocat/audit-" + n + ".git";
          p.ownerSubject = owner;
          p.privateRepository = false;
          p.persist();
          ProjectMember m = new ProjectMember();
          m.projectId = p.id;
          m.subject = owner;
          m.role = ProjectMember.TeamRole.LEAD;
          m.createdAt = Instant.now();
          m.persist();
          return p.id;
        });
  }

  private static Long build(Long projectId) {
    return build(projectId, Build.Status.PASSED, null);
  }

  private static Long build(Long projectId, Build.Status status, String revision) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project project = Project.findById(projectId);
          Build b = new Build();
          b.projectId = projectId;
          b.number = Build.nextNumber(projectId);
          b.requestedBy = "lead-id";
          b.status = status;
          b.branch = "main";
          b.image = "alpine:3.23";
          b.exitCode = status == Build.Status.PASSED ? 0 : null;
          b.startedAt = Instant.now();
          b.revision = revision;
          b.repositoryUrl = project.repositoryUrl;
          b.persist();
          return b.id;
        });
  }

  @Test
  void anonymousCannotReadTheAuditLog() {
    given().get(PATH).then().statusCode(401);
    given().get(PATH + "/1").then().statusCode(401);
  }

  @Test
  void doesNotRecordAuthenticationFailures() {
    given()
        .contentType("application/json")
        .body("{\"name\":\"anonymous\",\"repositoryUrl\":\"https://github.com/repo.git\"}")
        .post("/api/projects")
        .then()
        .statusCode(401);
    assertEquals(0, count("project.create"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "lead-id"),
        @Claim(key = "preferred_username", value = "lead")
      })
  void nonAdminsCannotReadTheAuditLog() {
    given().get(PATH).then().statusCode(403);
    given().get(PATH + "/1").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void listsNewestFirstWithPaginationAndTotal() {
    Instant now = Instant.now();
    event(
        "project.create",
        AuditOutcome.SUCCESS,
        "admin-id",
        "admin",
        7L,
        "alpha",
        AuditTargetType.PROJECT,
        "7",
        "alpha",
        "Created project alpha",
        now.minus(3, ChronoUnit.MINUTES),
        Map.of());
    event(
        "project.update",
        AuditOutcome.SUCCESS,
        "admin-id",
        "admin",
        7L,
        "alpha",
        AuditTargetType.PROJECT,
        "7",
        "alpha",
        "Updated project alpha",
        now.minus(2, ChronoUnit.MINUTES),
        Map.of("changed", java.util.List.of("name")));
    event(
        "project.delete",
        AuditOutcome.SUCCESS,
        "admin-id",
        "admin",
        7L,
        "alpha",
        AuditTargetType.PROJECT,
        "7",
        "alpha",
        "Deleted project alpha",
        now.minus(1, ChronoUnit.MINUTES),
        Map.of());

    given()
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(3))
        .body("events", hasSize(3))
        .body(
            "events.summary",
            contains("Deleted project alpha", "Updated project alpha", "Created project alpha"));

    given()
        .queryParam("first", 1)
        .queryParam("max", 1)
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(3))
        .body("events", hasSize(1))
        .body("events[0].summary", equalTo("Updated project alpha"));

    given().queryParam("max", 100000).get(PATH).then().statusCode(400);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void rejectsInvalidPaginationAndNumericFilters() {
    given().queryParam("first", -1).get(PATH).then().statusCode(400);
    given().queryParam("max", 0).get(PATH).then().statusCode(400);
    given().queryParam("max", 201).get(PATH).then().statusCode(400);
    given().queryParam("first", "abc").get(PATH).then().statusCode(400);
    given().queryParam("max", "abc").get(PATH).then().statusCode(400);
    given().queryParam("project", "abc").get(PATH).then().statusCode(400);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void rejectsShortSearchTermsButAcceptsTwoCharacters() {
    given().queryParam("search", "a").get(PATH).then().statusCode(400);
    given().queryParam("search", " a ").get(PATH).then().statusCode(400);
    given().queryParam("search", "al").get(PATH).then().statusCode(200);
    given().queryParam("search", "").get(PATH).then().statusCode(200);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void pagesByRowOffsetNotByPageNumber() {
    Instant base = Instant.parse("2026-01-02T03:00:00Z");
    for (int i = 0; i < 7; i++) {
      event(
          "probe.page",
          AuditOutcome.SUCCESS,
          "admin-id",
          "admin",
          null,
          null,
          AuditTargetType.SETTINGS,
          null,
          null,
          "event " + i,
          base.plusSeconds(i),
          Map.of());
    }
    List<String> newestFirst =
        List.of("event 6", "event 5", "event 4", "event 3", "event 2", "event 1", "event 0");

    assertEquals(newestFirst.subList(0, 3), summaries(0, 3));
    assertEquals(newestFirst.subList(3, 6), summaries(3, 3));
    assertEquals(newestFirst.subList(6, 7), summaries(6, 3));
    assertEquals(List.of(), summaries(9, 3));
    assertEquals(newestFirst.subList(2, 5), summaries(2, 3));

    List<String> paged = new ArrayList<>();
    for (int first = 0; first < newestFirst.size(); first += 3) {
      paged.addAll(summaries(first, 3));
    }
    assertEquals(newestFirst, paged);
    assertEquals((long) newestFirst.size(), paged.stream().distinct().count());
  }

  private static List<String> summaries(int first, int max) {
    return given()
        .queryParam("first", first)
        .queryParam("max", max)
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(7))
        .extract()
        .jsonPath()
        .getList("events.summary", String.class);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void largeFirstOffsetDoesNotOverflowRangeEnd() {
    given()
        .queryParam("first", Integer.MAX_VALUE)
        .get(PATH)
        .then()
        .statusCode(200)
        .body("events", hasSize(0));
    given()
        .queryParam("first", Integer.MAX_VALUE - 10)
        .queryParam("max", 50)
        .get(PATH)
        .then()
        .statusCode(200)
        .body("events", hasSize(0));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void filtersByEveryDocumentedField() {
    Instant now = Instant.now();
    event(
        "project.create",
        AuditOutcome.SUCCESS,
        "admin-id",
        "admin",
        7L,
        "alpha",
        AuditTargetType.PROJECT,
        "7",
        "alpha",
        "Created project alpha",
        now.minus(3, ChronoUnit.MINUTES),
        Map.of());
    event(
        "build.pin",
        AuditOutcome.SUCCESS,
        "lead-id",
        "lead",
        8L,
        "beta",
        AuditTargetType.BUILD,
        "42",
        "#42",
        "Pinned build #42",
        now.minus(2, ChronoUnit.MINUTES),
        Map.of());
    event(
        "project.create",
        AuditOutcome.DENIED,
        "member-id",
        "member",
        null,
        null,
        AuditTargetType.PROJECT,
        null,
        null,
        "Create project",
        now.minus(1, ChronoUnit.MINUTES),
        Map.of("status", 403));

    given()
        .queryParam("action", "project.create")
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(2));
    given()
        .queryParam("outcome", "success")
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(2));
    given()
        .queryParam("outcome", "denied")
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(1))
        .body("events[0].actorSubject", equalTo("member-id"));
    given()
        .queryParam("actor", "lead")
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(1))
        .body("events[0].details", equalTo(Map.of()));
    given()
        .queryParam("actor", "member-id")
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(1));
    given().queryParam("project", 7).get(PATH).then().statusCode(200).body("total", equalTo(1));
    given()
        .queryParam("targetType", "build")
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(1));
    given().queryParam("target", "#42").get(PATH).then().statusCode(200).body("total", equalTo(1));
    given().queryParam("search", "beta").get(PATH).then().statusCode(200).body("total", equalTo(1));
    given()
        .queryParam("search", "MEMBER")
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(1));
    given()
        .queryParam("from", now.minus(150, ChronoUnit.SECONDS).toString())
        .queryParam("to", now.minus(90, ChronoUnit.SECONDS).toString())
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(1))
        .body("events[0].action", equalTo("build.pin"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void rejectsInvalidFilters() {
    given().queryParam("outcome", "maybe").get(PATH).then().statusCode(400);
    given().queryParam("targetType", "planet").get(PATH).then().statusCode(400);
    given().queryParam("from", "yesterday").get(PATH).then().statusCode(400);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void returnsOneEventAndHidesMissingOnes() {
    Long id =
        event(
            "settings.general.update",
            AuditOutcome.SUCCESS,
            "admin-id",
            "admin",
            null,
            null,
            AuditTargetType.SETTINGS,
            "1",
            "general",
            "Updated the general settings",
            Instant.now(),
            Map.of("articleEditors", "ADMIN"));
    given()
        .get(PATH + "/" + id)
        .then()
        .statusCode(200)
        .body("id", equalTo(id.intValue()))
        .body("action", equalTo("settings.general.update"))
        .body("outcome", equalTo("SUCCESS"))
        .body("actorRoles", contains("admin"))
        .body("details.articleEditors", equalTo("ADMIN"));
    given().get(PATH + "/999999999").then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void recordsProjectCreationOnceWithStableSubjectAndNoCredential() {
    String name = "created-" + UUID.randomUUID();
    given()
        .contentType("application/json")
        .body(
            "{\"name\":\""
                + name
                + "\",\"repositoryUrl\":\"https://github.com/octocat/"
                + name
                + ".git\"}")
        .post("/api/projects")
        .then()
        .statusCode(201);

    AuditEvent created = find("project.create", AuditOutcome.SUCCESS);
    assertEquals("admin-id", created.actorSubject);
    assertEquals("admin", created.actorUsername);
    assertEquals("PROJECT", created.targetType.name());
    assertEquals(name, created.projectName);
    assertEquals(name, created.targetName);
    assertFalse(created.details.containsKey("credentialRef"));
    assertFalse(created.details.containsKey("credential"));
    assertEquals(1, count("project.create"));
    assertEquals(1, count("project.member.add"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void recordsTheChosenTeamWhenAProjectIsCreated() {
    String teamName = "audit-create-team-" + UUID.randomUUID();
    Long teamId =
        given()
            .contentType("application/json")
            .body("{\"name\":\"" + teamName + "\"}")
            .post("/api/teams")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getLong("id");
    String name = "created-team-" + UUID.randomUUID();
    given()
        .contentType("application/json")
        .body(
            "{\"name\":\""
                + name
                + "\",\"repositoryUrl\":\"https://github.com/octocat/"
                + name
                + ".git\",\"privateRepository\":true,\"teamId\":"
                + teamId
                + "}")
        .post("/api/projects")
        .then()
        .statusCode(201);

    AuditEvent created = find("project.create", AuditOutcome.SUCCESS);
    assertEquals(teamId.longValue(), ((Number) created.details.get("teamId")).longValue());
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void recordsOneTeamMemberRemoveForEachTeamWhenAUserIsDeleted() {
    for (int i = 0; i < 2; i++) {
      Long teamId =
          given()
              .contentType("application/json")
              .body("{\"name\":\"delete-audit-team-" + UUID.randomUUID() + "\"}")
              .post("/api/teams")
              .then()
              .statusCode(201)
              .extract()
              .jsonPath()
              .getLong("id");
      given()
          .contentType("application/json")
          .body("{\"username\":\"member\"}")
          .post("/api/teams/" + teamId + "/members")
          .then()
          .statusCode(201);
    }

    given().delete("/api/users/member-id").then().statusCode(204);

    assertEquals(2, count("team.member.remove"), "one event per removed team membership");
    AuditEvent removed = find("team.member.remove", AuditOutcome.SUCCESS);
    assertEquals("admin-id", removed.actorSubject, "the deleting administrator is the actor");
    assertEquals("member-id", removed.targetId);
    assertEquals("member", removed.targetName);
    assertEquals("TEAM_MEMBER", removed.targetType.name());
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "member-id"),
        @Claim(key = "preferred_username", value = "member")
      })
  void recordsDeniedCallsWithoutChangingTheResponse() {
    given()
        .contentType("application/json")
        .header("X-Request-Id", "request-123")
        .body("{\"name\":\"denied\",\"repositoryUrl\":\"https://github.com/repo.git\"}")
        .post("/api/projects")
        .then()
        .statusCode(403);

    AuditEvent denied = find("project.create", AuditOutcome.DENIED);
    assertEquals("member-id", denied.actorSubject);
    assertEquals("member", denied.actorUsername);
    assertEquals("POST", denied.httpMethod);
    assertEquals("/api/projects", denied.path);
    assertEquals("request-123", denied.requestId);
    assertEquals(403, denied.statusCode);
    assertEquals(403, denied.details.get("status"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void recordsConflictsAsFailures() {
    String host = "conflict-" + UUID.randomUUID() + ".example";
    given()
        .contentType("application/json")
        .body("{\"name\":\"one\",\"host\":\"" + host + "\"}")
        .post("/api/network-hosts")
        .then()
        .statusCode(201);
    given()
        .contentType("application/json")
        .body("{\"name\":\"two\",\"host\":\"" + host + "\"}")
        .post("/api/network-hosts")
        .then()
        .statusCode(409);

    AuditEvent failure = find("network.host.create", AuditOutcome.FAILURE);
    assertEquals("admin-id", failure.actorSubject);
    assertEquals(409, failure.statusCode);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void keepsTheProjectSnapshotAfterDeletion() {
    String name = "deleted-" + UUID.randomUUID();
    Long id =
        given()
            .contentType("application/json")
            .body(
                "{\"name\":\""
                    + name
                    + "\",\"repositoryUrl\":\"https://github.com/octocat/"
                    + name
                    + ".git\"}")
            .post("/api/projects")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getLong("id");
    given().delete("/api/projects/" + id).then().statusCode(204);

    AuditEvent deleted = find("project.delete", AuditOutcome.SUCCESS);
    assertEquals(id, deleted.projectId);
    assertEquals(name, deleted.projectName);
    assertEquals(String.valueOf(id), deleted.targetId);
    assertEquals(name, deleted.targetName);
    assertNull(QuarkusTransaction.requiringNew().call(() -> Project.findById(id)));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void recordsArticleLifecycle() {
    String title = "Article " + UUID.randomUUID();
    Long id =
        given()
            .contentType("application/json")
            .body("{\"title\":\"" + title + "\",\"body\":\"# Hello\"}")
            .post("/api/articles")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getLong("id");
    given()
        .contentType("application/json")
        .body("{\"title\":\"" + title + " v2\",\"body\":\"# Hello again\"}")
        .put("/api/articles/" + id)
        .then()
        .statusCode(200);
    given().delete("/api/articles/" + id).then().statusCode(204);

    assertEquals(1, count("article.create"));
    assertEquals(1, count("article.update"));
    assertEquals(1, count("article.delete"));
    AuditEvent updated = find("article.update", AuditOutcome.SUCCESS);
    assertEquals(java.util.List.of("title", "body"), updated.details.get("changed"));
    AuditEvent deleted = find("article.delete", AuditOutcome.SUCCESS);
    assertEquals(title + " v2", deleted.targetName);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "lead-id"),
        @Claim(key = "preferred_username", value = "lead")
      })
  void recordsBuildPinAndUnpin() {
    Long projectId = project("lead-id");
    Long buildId = build(projectId);
    given()
        .put("/api/projects/" + projectId + "/builds/" + buildId + "/pin")
        .then()
        .statusCode(200);
    given()
        .delete("/api/projects/" + projectId + "/builds/" + buildId + "/pin")
        .then()
        .statusCode(200);

    assertEquals(1, count("build.pin"));
    assertEquals(1, count("build.unpin"));
    AuditEvent pinned = find("build.pin", AuditOutcome.SUCCESS);
    assertEquals("lead-id", pinned.actorSubject);
    assertEquals(projectId, pinned.projectId);
    assertEquals(String.valueOf(buildId), pinned.targetId);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "member-id"),
        @Claim(key = "preferred_username", value = "member")
      })
  void recordsDeniedBuildActions() {
    Long projectId = project("lead-id");
    Long buildId = build(projectId);
    given()
        .put("/api/projects/" + projectId + "/builds/" + buildId + "/pin")
        .then()
        .statusCode(403);

    AuditEvent denied = find("build.pin", AuditOutcome.DENIED);
    assertEquals("member-id", denied.actorSubject);
    assertEquals(projectId, denied.projectId);
    assertEquals(String.valueOf(buildId), denied.targetId);
    assertEquals(403, denied.statusCode);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void imageRemovalSurvivesAnAuditInsertFailure() {
    String image = "audit-" + UUID.randomUUID() + ":1";
    QuarkusTransaction.requiringNew()
        .run(() -> {
          AllowedImage allowed = new AllowedImage();
          allowed.name = image;
          allowed.persist();
        });
    BuildRunner fake =
        new BuildRunner() {
          @Override
          Optional<String> remove(String name) {
            return Optional.empty();
          }

          @Override
          List<LocalImage> images() {
            return List.of();
          }
        };
    QuarkusMock.installMockForType(fake, BuildRunner.class);
    List<String> warnings = new ArrayList<>();
    Logger logger = Logger.getLogger(AuditService.class.getName());
    Handler handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            warnings.add(record.getMessage());
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(handler);
    try {
      installFailingAuditTrigger();
      given().delete("/api/images/local?name=" + image).then().statusCode(200);
      assertEquals(0, count("image.remove"));
      assertEquals(
          0, QuarkusTransaction.requiringNew().call(() -> AllowedImage.count("name = ?1", image)));
      assertTrue(
          warnings.stream()
              .anyMatch(m -> m != null && m.contains("Could not write an audit event")));
    } finally {
      logger.removeHandler(handler);
      QuarkusTransaction.requiringNew().run(() -> AllowedImage.deleteById(image));
      removeFailingAuditTrigger();
    }
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void redactsAWholeJwtInAnArticleTitle() {
    given()
        .contentType("application/json")
        .body("{\"title\":\"" + JWT + "\",\"body\":\"# Hello\"}")
        .post("/api/articles")
        .then()
        .statusCode(201);

    AuditEvent created = find("article.create", AuditOutcome.SUCCESS);
    assertFalse(created.summary.contains(JWT));
    assertFalse(created.targetName.contains(JWT));
    assertEquals("[redacted]", created.targetName);
    assertEquals("[redacted]", created.details.get("title"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void redactsAnEmbeddedJwtInProse() {
    String title = "Token " + JWT + " pasted here";
    given()
        .contentType("application/json")
        .body("{\"title\":\"" + title + "\",\"body\":\"# Hello\"}")
        .post("/api/articles")
        .then()
        .statusCode(201);

    AuditEvent created = find("article.create", AuditOutcome.SUCCESS);
    assertFalse(created.summary.contains(JWT));
    assertFalse(created.targetName.contains(JWT));
    assertTrue(created.summary.contains("[redacted]"));
    assertTrue(created.targetName.contains("[redacted]"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "lead-id"),
        @Claim(key = "preferred_username", value = "lead")
      })
  void redactsTheProjectNameSnapshot() {
    String jwt = "aaaaaaaaaaaaaaaa.bbbbbbbb.cccccccc";
    Long projectId = project("lead-id", "p-" + jwt);
    Long buildId = build(projectId);
    given()
        .put("/api/projects/" + projectId + "/builds/" + buildId + "/pin")
        .then()
        .statusCode(200);

    AuditEvent pinned = find("build.pin", AuditOutcome.SUCCESS);
    assertFalse(pinned.projectName.contains(jwt));
    assertTrue(pinned.projectName.contains("[redacted]"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = JWT)
      })
  void redactsTheActorUsername() {
    given()
        .contentType("application/json")
        .body("{\"title\":\"Actor note\",\"body\":\"# Hello\"}")
        .post("/api/articles")
        .then()
        .statusCode(201);

    AuditEvent created = find("article.create", AuditOutcome.SUCCESS);
    assertEquals("[redacted]", created.actorUsername);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void recordsProjectUpdateBeforeAndAfterTheChange() {
    String before = "before-" + UUID.randomUUID();
    String after = "after-" + UUID.randomUUID();
    Long id =
        given()
            .contentType("application/json")
            .body(
                "{\"name\":\""
                    + before
                    + "\",\"repositoryUrl\":\"https://github.com/octocat/"
                    + before
                    + ".git\"}")
            .post("/api/projects")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getLong("id");

    given()
        .contentType("application/json")
        .body(
            "{\"name\":\""
                + after
                + "\",\"repositoryUrl\":\"https://github.com/octocat/"
                + after
                + ".git\",\"privateRepository\":true}")
        .put("/api/projects/" + id)
        .then()
        .statusCode(200);

    AuditEvent updated = find("project.update", AuditOutcome.SUCCESS);
    assertEquals(before, updated.details.get("nameBefore"));
    assertEquals(after, updated.details.get("nameAfter"));
    assertEquals(
        "https://github.com/octocat/" + before, updated.details.get("repositoryUrlBefore"));
    assertEquals("https://github.com/octocat/" + after, updated.details.get("repositoryUrlAfter"));
    assertEquals(false, updated.details.get("privateRepositoryBefore"));
    assertEquals(true, updated.details.get("privateRepositoryAfter"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void recordsSettingsBeforeAndAfterTheChange() {
    seedSettings();

    given()
        .contentType("application/json")
        .body("{\"articleEditors\":\"MEMBER\"}")
        .put("/api/settings/general")
        .then()
        .statusCode(200);
    given()
        .contentType("application/json")
        .body("{\"maxAgeDays\":7,\"maxBuilds\":9}")
        .put("/api/settings/retention")
        .then()
        .statusCode(200);
    given()
        .contentType("application/json")
        .body("{\"maxConcurrent\":5,\"maxQueued\":6,\"timeoutMinutes\":7}")
        .put("/api/settings/builds")
        .then()
        .statusCode(200);
    given()
        .contentType("application/json")
        .body("{\"maxSizeMib\":200,\"maxFiles\":2000}")
        .put("/api/settings/checkout")
        .then()
        .statusCode(200);
    given()
        .contentType("application/json")
        .body("{\"maxMemoryMib\":1024,\"maxCpus\":4,\"maxPids\":256}")
        .put("/api/settings/performance")
        .then()
        .statusCode(200);
    given()
        .contentType("application/json")
        .body(
            "{\"enabled\":false,\"granularity\":\"TIP\",\"scope\":\"MAIN_ONLY\","
                + "\"intervalSeconds\":120,\"mirrorManualBuilds\":true}")
        .put("/api/settings/polling")
        .then()
        .statusCode(200);
    given()
        .contentType("application/json")
        .body("{\"enabled\":true}")
        .put("/api/settings/pull-requests")
        .then()
        .statusCode(200);

    AuditEvent general = find("settings.general.update", AuditOutcome.SUCCESS);
    assertEquals("ADMIN", general.details.get("articleEditorsBefore"));
    assertEquals("MEMBER", general.details.get("articleEditorsAfter"));

    AuditEvent retention = find("settings.retention.update", AuditOutcome.SUCCESS);
    assertEquals(30, retention.details.get("maxAgeDaysBefore"));
    assertEquals(7, retention.details.get("maxAgeDaysAfter"));

    AuditEvent builds = find("settings.builds.update", AuditOutcome.SUCCESS);
    assertEquals(2, builds.details.get("maxConcurrentBefore"));
    assertEquals(5, builds.details.get("maxConcurrentAfter"));

    AuditEvent checkout = find("settings.checkout.update", AuditOutcome.SUCCESS);
    assertEquals(100, checkout.details.get("maxSizeMibBefore"));
    assertEquals(200, checkout.details.get("maxSizeMibAfter"));

    AuditEvent performance = find("settings.performance.update", AuditOutcome.SUCCESS);
    assertEquals(512, performance.details.get("maxMemoryMibBefore"));
    assertEquals(1024, performance.details.get("maxMemoryMibAfter"));

    AuditEvent polling = find("settings.polling.update", AuditOutcome.SUCCESS);
    assertEquals(true, polling.details.get("enabledBefore"));
    assertEquals(false, polling.details.get("enabledAfter"));

    AuditEvent pullRequests = find("settings.pullrequests.update", AuditOutcome.SUCCESS);
    assertEquals(false, pullRequests.details.get("enabledBefore"));
    assertEquals(true, pullRequests.details.get("enabledAfter"));
  }

  private static void seedSettings() {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Settings s = new Settings();
          s.retentionDays = 30;
          s.retentionMaxBuilds = 50;
          s.maxConcurrentBuilds = 2;
          s.maxQueuedBuilds = 3;
          s.buildTimeoutMinutes = 4;
          s.maxCheckoutMib = 100;
          s.maxCheckoutFiles = 1000;
          s.maxBuildMemoryMib = 512;
          s.maxBuildCpus = 2;
          s.maxBuildPids = 128;
          s.pollEnabled = true;
          s.pollGranularity = Settings.Granularity.PER_COMMIT;
          s.pollScope = Settings.Scope.WORKFLOW;
          s.pollIntervalSeconds = 60;
          s.mirrorManualBuilds = false;
          s.pullRequestsEnabled = false;
          s.articleEditors = Settings.ArticleEditors.ADMIN;
          s.persist();
        });
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void escapesLikeWildcards() {
    event(
        "probe",
        AuditOutcome.SUCCESS,
        "admin-id",
        "admin",
        null,
        null,
        AuditTargetType.SETTINGS,
        null,
        null,
        "100% done",
        Instant.now(),
        Map.of());
    event(
        "probe",
        AuditOutcome.SUCCESS,
        "admin-id",
        "admin",
        null,
        null,
        AuditTargetType.SETTINGS,
        null,
        null,
        "100x done",
        Instant.now(),
        Map.of());
    event(
        "probe",
        AuditOutcome.SUCCESS,
        "admin-id",
        "admin",
        null,
        null,
        AuditTargetType.SETTINGS,
        null,
        null,
        "under_score",
        Instant.now(),
        Map.of());
    event(
        "probe",
        AuditOutcome.SUCCESS,
        "admin-id",
        "admin",
        null,
        null,
        AuditTargetType.SETTINGS,
        null,
        null,
        "underXscore",
        Instant.now(),
        Map.of());

    given().queryParam("search", "100%").get(PATH).then().statusCode(200).body("total", equalTo(1));
    given()
        .queryParam("search", "under_score")
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(1));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void networkTemplatesHaveTheirOwnTargetType() {
    given().post("/api/network-hosts/templates/npm").then().statusCode(200);

    AuditEvent added = find("network.template.add", AuditOutcome.SUCCESS);
    assertEquals("NETWORK_TEMPLATE", added.targetType.name());
    assertEquals("npm", added.targetId);
    assertEquals("npm", added.targetName);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void projectCreationRollsBackWhenTheAuditInsertFails() {
    installFailingAuditTrigger();
    try {
      String name = "atomic-" + UUID.randomUUID();
      given()
          .contentType("application/json")
          .body(
              "{\"name\":\""
                  + name
                  + "\",\"repositoryUrl\":\"https://github.com/octocat/"
                  + name
                  + ".git\"}")
          .post("/api/projects")
          .then()
          .statusCode(500);
      assertEquals(
          0, QuarkusTransaction.requiringNew().call(() -> Project.count("name = ?1", name)));
      assertEquals(0, count("project.create"));
    } finally {
      removeFailingAuditTrigger();
    }
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void recordsUserLifecycle() {
    String username = "user" + UUID.randomUUID().toString().substring(0, 8);
    String id =
        given()
            .contentType("application/json")
            .body(
                "{\"username\":\""
                    + username
                    + "\",\"password\":\"secret-pass\",\"role\":\"member\",\"projectIds\":[]}")
            .post("/api/users")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");
    given()
        .contentType("application/json")
        .body("{\"enabled\":false}")
        .put("/api/users/" + id)
        .then()
        .statusCode(200);
    given()
        .contentType("application/json")
        .body("{\"password\":\"another-secret\"}")
        .put("/api/users/" + id + "/password")
        .then()
        .statusCode(204);
    given().delete("/api/users/" + id).then().statusCode(204);

    AuditEvent created = find("user.create", AuditOutcome.SUCCESS);
    assertEquals(username, created.targetName);
    assertFalse(created.details.containsKey("password"));
    AuditEvent updated = find("user.update", AuditOutcome.SUCCESS);
    assertTrue(((java.util.List<?>) updated.details.get("changed")).contains("enabled"));
    AuditEvent password = find("user.password.reset", AuditOutcome.SUCCESS);
    assertFalse(password.details.containsValue("another-secret"));
    assertEquals(1, count("user.delete"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void recordsBrandingAndImageOperations() {
    String brandName = "Brand-" + UUID.randomUUID();
    given()
        .contentType("application/json")
        .body(
            "{\"name\":\""
                + brandName
                + "\",\"headerColor\":\"#123456\",\"headingColor\":\"#345678\","
                + "\"buttonColor\":\"#567890\"}")
        .put("/api/branding")
        .then()
        .statusCode(200);

    AuditEvent branding = find("branding.update", AuditOutcome.SUCCESS);
    assertEquals("admin-id", branding.actorSubject);
    assertEquals("BRANDING", branding.targetType.name());
    assertTrue(((List<?>) branding.details.get("changed")).contains("name"));
    assertEquals(brandName, branding.details.get("name"));

    BuildRunner fake =
        new BuildRunner() {
          @Override
          Optional<String> pull(String name) {
            return Optional.empty();
          }

          @Override
          Optional<String> remove(String name) {
            return Optional.empty();
          }

          @Override
          List<LocalImage> images() {
            return List.of();
          }
        };
    QuarkusMock.installMockForType(fake, BuildRunner.class);

    given()
        .contentType("application/json")
        .body("{\"images\":[\"alpine:3.23\"]}")
        .put("/api/images")
        .then()
        .statusCode(200);
    given()
        .contentType("application/json")
        .body("{\"name\":\"alpine:3.23\"}")
        .post("/api/images/local")
        .then()
        .statusCode(200);
    given().queryParam("name", "alpine:3.23").delete("/api/images/local").then().statusCode(200);

    AuditEvent allowlist = find("image.allowlist.update", AuditOutcome.SUCCESS);
    assertEquals("IMAGE", allowlist.targetType.name());
    AuditEvent pull = find("image.pull", AuditOutcome.SUCCESS);
    assertEquals("alpine:3.23", pull.targetId);
    AuditEvent remove = find("image.remove", AuditOutcome.SUCCESS);
    assertEquals("alpine:3.23", remove.targetId);
    assertEquals(true, remove.details.get("removedFromAllowlist"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void recordsProjectCredentialChangesWithoutTheReference() {
    String name = "credential-" + UUID.randomUUID();
    Long id =
        given()
            .contentType("application/json")
            .body(
                "{\"name\":\""
                    + name
                    + "\",\"repositoryUrl\":\"https://github.com/octocat/"
                    + name
                    + ".git\",\"privateRepository\":true}")
            .post("/api/projects")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getLong("id");

    given()
        .contentType("application/json")
        .body("{\"credentialRef\":\"CISYS_GIT_TEST\"}")
        .put("/api/projects/" + id + "/credential")
        .then()
        .statusCode(200);
    AuditEvent set = find("project.credential.update", AuditOutcome.SUCCESS);
    assertEquals("PROJECT", set.targetType.name());
    assertEquals(id, set.projectId);
    assertEquals(Map.of("cleared", false), set.details);
    assertFalse(set.details.toString().contains("CISYS_GIT_TEST"));
    long afterSet = count("project.credential.update");

    // Writing the same reference again is a no-op and must not add an audit event.
    given()
        .contentType("application/json")
        .body("{\"credentialRef\":\"CISYS_GIT_TEST\"}")
        .put("/api/projects/" + id + "/credential")
        .then()
        .statusCode(200);
    assertEquals(afterSet, count("project.credential.update"));

    given()
        .contentType("application/json")
        .body("{}")
        .put("/api/projects/" + id + "/credential")
        .then()
        .statusCode(200);
    assertEquals(afterSet + 1, count("project.credential.update"));
    AuditEvent cleared = find("project.credential.update", AuditOutcome.SUCCESS);
    assertEquals(Map.of("cleared", true), cleared.details);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "lead-id"),
        @Claim(key = "preferred_username", value = "lead")
      })
  void recordsProjectTeamChanges() {
    Long id = project("lead-id");

    given()
        .contentType("application/json")
        .body("{\"username\":\"member\",\"role\":\"MEMBER\"}")
        .post("/api/projects/" + id + "/team")
        .then()
        .statusCode(201);
    AuditEvent added = find("project.member.add", AuditOutcome.SUCCESS);
    assertEquals("lead-id", added.actorSubject);
    assertEquals("PROJECT_MEMBER", added.targetType.name());
    assertEquals("member-id", added.targetId);
    assertEquals("member", added.targetName);
    assertEquals("MEMBER", added.details.get("role"));

    given()
        .contentType("application/json")
        .body("{\"role\":\"LEAD\"}")
        .put("/api/projects/" + id + "/team/member")
        .then()
        .statusCode(200);
    AuditEvent role = find("project.member.role", AuditOutcome.SUCCESS);
    assertEquals("MEMBER", role.details.get("previous"));
    assertEquals("LEAD", role.details.get("current"));

    given().delete("/api/projects/" + id + "/team/member").then().statusCode(204);
    AuditEvent removed = find("project.member.remove", AuditOutcome.SUCCESS);
    assertEquals("member-id", removed.targetId);
    assertEquals("member", removed.targetName);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "lead-id"),
        @Claim(key = "preferred_username", value = "lead")
      })
  void recordsTeamChangesWithoutTheToken() {
    String name = "audit-team-" + UUID.randomUUID();
    Long teamId =
        given()
            .contentType("application/json")
            .body("{\"name\":\"" + name + "\"}")
            .post("/api/teams")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getLong("id");
    assertEquals(1, count("team.create"));
    assertEquals(1, count("team.member.add"));

    given()
        .contentType("application/json")
        .body("{\"name\":\"" + name + "-renamed\"}")
        .put("/api/teams/" + teamId)
        .then()
        .statusCode(200);
    assertEquals("TEAM", find("team.update", AuditOutcome.SUCCESS).targetType.name());

    given()
        .contentType("application/json")
        .body("{\"username\":\"member\",\"role\":\"MEMBER\"}")
        .post("/api/teams/" + teamId + "/members")
        .then()
        .statusCode(201);
    given()
        .contentType("application/json")
        .body("{\"role\":\"LEAD\"}")
        .put("/api/teams/" + teamId + "/members/member")
        .then()
        .statusCode(200);
    given()
        .contentType("application/json")
        .body("{\"role\":\"MEMBER\"}")
        .put("/api/teams/" + teamId + "/members/member")
        .then()
        .statusCode(200);
    given().delete("/api/teams/" + teamId + "/members/member").then().statusCode(204);
    assertTrue(count("team.member.add") >= 2);
    assertTrue(count("team.member.role") >= 2);
    assertEquals(1, count("team.member.remove"));

    String secret = "audit-token-secret-" + UUID.randomUUID();
    given()
        .contentType("application/json")
        .body("{\"token\":\"" + secret + "\"}")
        .put("/api/teams/" + teamId + "/token")
        .then()
        .statusCode(200);
    AuditEvent set = find("team.token.update", AuditOutcome.SUCCESS);
    assertEquals(Map.of("set", true), set.details);
    assertFalse(set.details.toString().contains(secret));
    given().delete("/api/teams/" + teamId + "/token").then().statusCode(200);
    AuditEvent cleared = find("team.token.update", AuditOutcome.SUCCESS);
    assertEquals(Map.of("set", false), cleared.details);
    assertFalse(cleared.details.toString().contains(secret));

    Long projectId = project("lead-id");
    given()
        .contentType("application/json")
        .body("{\"teamId\":" + teamId + "}")
        .put("/api/projects/" + projectId + "/team-assignment")
        .then()
        .statusCode(200);
    given()
        .contentType("application/json")
        .body("{}")
        .put("/api/projects/" + projectId + "/team-assignment")
        .then()
        .statusCode(200);
    assertEquals(2, count("project.team.update"));

    // Attach again so that deleting the team has a project to detach.
    given()
        .contentType("application/json")
        .body("{\"teamId\":" + teamId + "}")
        .put("/api/projects/" + projectId + "/team-assignment")
        .then()
        .statusCode(200);
    assertEquals(3, count("project.team.update"));

    given().delete("/api/teams/" + teamId).then().statusCode(204);
    AuditEvent deleted = find("team.delete", AuditOutcome.SUCCESS);
    assertEquals("TEAM", deleted.targetType.name());
    assertEquals("lead-id", deleted.actorSubject);

    // Deleting a team writes one detachment event for each project it was attached to.
    assertEquals(4, count("project.team.update"));
    AuditEvent detachedEvent = find("project.team.update", AuditOutcome.SUCCESS);
    assertEquals("PROJECT", detachedEvent.targetType.name());
    assertEquals(String.valueOf(projectId), detachedEvent.targetId);
    assertEquals(projectId, detachedEvent.projectId);
    assertEquals(
        teamId.longValue(), ((Number) detachedEvent.details.get("teamBefore")).longValue());
    assertEquals(null, detachedEvent.details.get("teamAfter"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "lead-id"),
        @Claim(key = "preferred_username", value = "lead")
      })
  void recordsBuildStartRerunAndCancel() {
    Long id = project("lead-id");
    Long finished = build(id, Build.Status.PASSED, "abcdef1234567890");
    Long queued = build(id, Build.Status.QUEUED, null);

    BuildRunner fake =
        new BuildRunner() {
          @Override
          Build submit(
              Long projectId,
              String actor,
              String url,
              String branch,
              String credentialRef,
              String revision,
              Build.Trigger trigger,
              String triggerActor,
              Integer pullRequest,
              String pullRequestProvider) {
            return QuarkusTransaction.requiringNew()
                .call(() -> {
                  Build b = new Build();
                  b.projectId = projectId;
                  b.number = Build.nextNumber(projectId);
                  b.requestedBy = actor;
                  b.status = Build.Status.QUEUED;
                  b.branch = branch;
                  b.revision = revision;
                  b.repositoryUrl = url;
                  b.trigger = trigger == null ? Build.Trigger.MANUAL : trigger;
                  b.startedAt = Instant.now();
                  b.persist();
                  return b;
                });
          }

          @Override
          boolean cancel(Long buildId) {
            return true;
          }
        };
    QuarkusMock.installMockForType(fake, BuildRunner.class);

    Long started =
        given()
            .contentType("application/json")
            .body("{\"branch\":\"main\"}")
            .post("/api/projects/" + id + "/builds")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getLong("id");
    given()
        .contentType("application/json")
        .post("/api/projects/" + id + "/builds/" + finished + "/rerun")
        .then()
        .statusCode(200);
    given()
        .contentType("application/json")
        .post("/api/projects/" + id + "/builds/" + queued + "/cancel")
        .then()
        .statusCode(200);

    assertEquals(1, count("build.start"));
    assertEquals(1, count("build.rerun"));
    assertEquals(1, count("build.cancel"));

    AuditEvent start = find("build.start", AuditOutcome.SUCCESS);
    assertEquals("lead-id", start.actorSubject);
    assertEquals(id, start.projectId);
    assertEquals(String.valueOf(started), start.targetId);
    assertEquals("main", start.details.get("branch"));

    AuditEvent rerun = find("build.rerun", AuditOutcome.SUCCESS);
    assertEquals(id, rerun.projectId);
    assertEquals("BUILD", rerun.targetType.name());

    AuditEvent cancel = find("build.cancel", AuditOutcome.SUCCESS);
    assertEquals(String.valueOf(queued), cancel.targetId);
    assertEquals("main", cancel.details.get("branch"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void recordsNetworkHostLifecycleAndTemplate() {
    Long id =
        given()
            .contentType("application/json")
            .body("{\"name\":\"npm\",\"host\":\"registry.npmjs.org\"}")
            .post("/api/network-hosts")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getLong("id");

    given()
        .contentType("application/json")
        .body("{\"name\":\"Yarn\",\"host\":\"registry.yarnpkg.com\"}")
        .put("/api/network-hosts/" + id)
        .then()
        .statusCode(200);
    given().post("/api/network-hosts/templates/npm").then().statusCode(200);
    given().delete("/api/network-hosts/" + id).then().statusCode(204);

    AuditEvent created = find("network.host.create", AuditOutcome.SUCCESS);
    assertEquals("registry.npmjs.org", created.targetName);
    assertEquals("npm", created.details.get("name"));

    AuditEvent updated = find("network.host.update", AuditOutcome.SUCCESS);
    assertEquals("registry.yarnpkg.com", updated.targetName);
    assertTrue(((List<?>) updated.details.get("changed")).contains("host"));

    AuditEvent template = find("network.template.add", AuditOutcome.SUCCESS);
    assertEquals("NETWORK_TEMPLATE", template.targetType.name());

    AuditEvent deleted = find("network.host.delete", AuditOutcome.SUCCESS);
    assertEquals(String.valueOf(id), deleted.targetId);
    assertEquals("registry.yarnpkg.com", deleted.targetName);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void recordsABadGatewayAsAFailure() {
    TestUserDirectory down =
        new TestUserDirectory() {
          @Override
          public String create(UserDirectory.User user, String password) {
            throw new UserDirectory.UnavailableException("down");
          }
        };
    QuarkusMock.installMockForType(down, TestUserDirectory.class);

    given()
        .contentType("application/json")
        .body(
            "{\"username\":\"unavailable\",\"password\":\"secret-pass\","
                + "\"role\":\"member\",\"projectIds\":[]}")
        .post("/api/users")
        .then()
        .statusCode(502);

    AuditEvent failure = find("user.create", AuditOutcome.FAILURE);
    assertEquals("admin-id", failure.actorSubject);
    assertEquals(502, failure.statusCode);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void doesNotRecordValidationMissingOrThrottledResponses() {
    given()
        .contentType("application/json")
        .body("{\"username\":\"bad-role\",\"password\":\"secret-pass\",\"role\":\"planet\"}")
        .post("/api/users")
        .then()
        .statusCode(400);
    assertEquals(0, count("user.create"));

    given().delete("/api/projects/999999999").then().statusCode(404);
    assertEquals(0, count("project.delete"));

    BuildRunner full =
        new BuildRunner() {
          @Override
          Build submit(Long projectId, String actor, String url, BuildResource.RunRequest request) {
            throw new WebApplicationException(Response.status(429).build());
          }
        };
    QuarkusMock.installMockForType(full, BuildRunner.class);
    Long id = project("lead-id");
    given()
        .contentType("application/json")
        .body("{\"branch\":\"main\"}")
        .post("/api/projects/" + id + "/builds")
        .then()
        .statusCode(429);
    assertEquals(0, count("build.start"));
  }
}
