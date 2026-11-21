/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.restassured.path.json.JsonPath;
import io.restassured.path.json.config.JsonPathConfig;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BuildStatisticsResourceTest {
  private static final String LEAD = "report-lead-id";
  private static final JsonPathConfig DOUBLES =
      new JsonPathConfig(JsonPathConfig.NumberReturnType.DOUBLE);

  private static Long project(String lead) {
    String id = UUID.randomUUID().toString();
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project p = new Project();
          p.name = "report-" + id;
          p.repositoryUrl = "https://github.com/test/report-" + id + ".git";
          p.ownerSubject = lead;
          p.persist();
          membership(p.id, lead, ProjectMember.TeamRole.LEAD);
          return p.id;
        });
  }

  private static void membership(Long projectId, String subject, ProjectMember.TeamRole role) {
    QuarkusTransaction.joiningExisting()
        .run(() -> {
          ProjectMember m = new ProjectMember();
          m.projectId = projectId;
          m.subject = subject;
          m.role = role;
          m.createdAt = Instant.now();
          m.persist();
        });
  }

  private static void teamLink(Long projectId, String subject) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Team team = new Team();
          team.name = "report-team-" + UUID.randomUUID();
          team.ownerSubject = subject;
          team.createdAt = Instant.now();
          team.persistAndFlush();

          TeamMember member = new TeamMember();
          member.teamId = team.id;
          member.subject = subject;
          member.role = ProjectMember.TeamRole.LEAD;
          member.createdAt = Instant.now();
          member.persist();

          Project project = Project.findById(projectId);
          project.teamId = team.id;
        });
  }

  private static void build(
      Long projectId, Build.Status status, Build.Trigger trigger, String branch, Instant at) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Build b = new Build();
          b.projectId = projectId;
          b.number = Build.nextNumber(projectId);
          b.requestedBy = LEAD;
          b.status = status;
          b.trigger = trigger;
          b.branch = branch;
          b.revision = "0123456789abcdef";
          b.requestedAt = at;
          b.startedAt = at;
          if (status != Build.Status.RUNNING && status != Build.Status.QUEUED) {
            b.finishedAt = at.plusSeconds(30);
          }
          b.persist();
        });
  }

  private static JsonPath statistics(String query) {
    return JsonPath.from(
            given().get("/api/reports/builds" + query).then().statusCode(200).extract().asString())
        .using(DOUBLES);
  }

  private static List<Long> projectIds(JsonPath s) {
    return s.getList("projects.id", Double.class).stream().map(Double::longValue).toList();
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "report-admin-id")})
  void adminSeesEveryProjectAndItsTotals() {
    Long a = project("someone-else"), b = project(LEAD);
    Instant now = Instant.now();
    build(a, Build.Status.PASSED, Build.Trigger.MANUAL, "main", now);
    build(a, Build.Status.PASSED, Build.Trigger.POLL, "main", now);
    build(a, Build.Status.FAILED, Build.Trigger.PULL_REQUEST, "feature", now);
    build(a, Build.Status.CANCELLED, Build.Trigger.MANUAL, "main", now);
    build(a, Build.Status.RUNNING, Build.Trigger.MANUAL, "main", now);

    List<Long> ids = projectIds(statistics(""));
    assertTrue(ids.contains(a) && ids.contains(b));

    JsonPath s = statistics("?projectId=" + a + "&period=all");
    assertEquals(5, s.getLong("totals.builds"));
    assertEquals(2, s.getLong("totals.passed"));
    assertEquals(1, s.getLong("totals.failed"));
    assertEquals(1, s.getLong("totals.cancelled"));
    assertEquals(1, s.getLong("totals.active"));
    assertEquals(2.0 / 3, s.getDouble("totals.successRate"), 1e-9);
    assertEquals(30.0, s.getDouble("totals.averageSeconds"), 1e-6);
    assertEquals(1, s.getList("byProject").size());
    assertEquals(3, s.getList("byTrigger").size());
    assertEquals(List.of("main", "feature"), s.getList("byBranch.key"));
    assertEquals(1, s.getList("recentFailures").size());
    assertEquals("feature", s.getString("recentFailures[0].branch"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "report-admin-id")})
  void periodLeavesOutOlderBuilds() {
    Long p = project(LEAD);
    build(p, Build.Status.PASSED, Build.Trigger.MANUAL, "main", Instant.now());
    build(
        p,
        Build.Status.PASSED,
        Build.Trigger.MANUAL,
        "main",
        Instant.now().minus(60, ChronoUnit.DAYS));
    assertEquals(1, statistics("?projectId=" + p + "&period=30d").getLong("totals.builds"));
    assertEquals(2, statistics("?projectId=" + p + "&period=all").getLong("totals.builds"));
    given().get("/api/reports/builds?period=week").then().statusCode(400);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = LEAD)})
  void leadSeesOnlyTheProjectsTheyLead() {
    Long led = project(LEAD), other = project("someone-else"), joined = project("someone-else");
    QuarkusTransaction.requiringNew()
        .run(() -> membership(joined, LEAD, ProjectMember.TeamRole.MEMBER));
    build(other, Build.Status.FAILED, Build.Trigger.MANUAL, "main", Instant.now());

    JsonPath s = statistics("");
    List<Long> ids = projectIds(s);
    assertTrue(ids.contains(led));
    assertFalse(ids.contains(other));
    assertFalse(ids.contains(joined));
    assertFalse(s.getList("recentFailures.projectId", Double.class).contains(other.doubleValue()));
    given().get("/api/reports/builds?projectId=" + other).then().statusCode(403);
    given().get("/api/reports/builds?projectId=" + joined).then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "team-report-lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "team-report-lead-id")})
  void teamLeadCanSeeReportsWithoutADirectProjectRow() {
    Long linked = project("someone-else");
    teamLink(linked, "team-report-lead-id");
    build(linked, Build.Status.PASSED, Build.Trigger.MANUAL, "main", Instant.now());

    JsonPath s = statistics("?projectId=" + linked);
    assertEquals(List.of(linked), projectIds(s));
    assertEquals(1, s.getLong("totals.builds"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "report-lead-without-projects")})
  void leadWithoutProjectsGetsAnEmptyReport() {
    JsonPath s = statistics("");
    assertEquals(0, s.getList("projects").size());
    assertEquals(0, s.getLong("totals.builds"));
    assertNull(s.get("totals.successRate"));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "report-member-id")})
  void membersCannotSeeReports() {
    given().get("/api/reports/builds").then().statusCode(403);
    given().get("/api/reports/builds/pdf").then().statusCode(403);
  }

  @Test
  void anonymousCannotSeeReports() {
    given().get("/api/reports/builds").then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "report-admin-id")})
  void exportsAPdf() {
    Long p = project(LEAD);
    build(p, Build.Status.FAILED, Build.Trigger.MANUAL, "main", Instant.now());
    byte[] pdf =
        given()
            .get("/api/reports/builds/pdf?projectId=" + p)
            .then()
            .statusCode(200)
            .contentType("application/pdf")
            .header("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;"))
            .extract()
            .asByteArray();
    assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
    // The trends section embeds one rendered chart image per chart.
    String raw = new String(pdf, StandardCharsets.ISO_8859_1);
    int images = 0;
    for (int at = raw.indexOf("/Subtype/Image");
        at >= 0;
        at = raw.indexOf("/Subtype/Image", at + 1)) {
      images++;
    }
    assertTrue(images >= 6, "expected at least six chart images, found " + images);
  }
}
