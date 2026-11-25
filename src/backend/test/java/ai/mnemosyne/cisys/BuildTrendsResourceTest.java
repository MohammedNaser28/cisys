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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

@QuarkusTest
class BuildTrendsResourceTest {
  private static final String LEAD = "trend-lead-id";
  private static final JsonPathConfig DOUBLES =
      new JsonPathConfig(JsonPathConfig.NumberReturnType.DOUBLE);

  private static Long project(String lead) {
    String id = UUID.randomUUID().toString();
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project p = new Project();
          p.name = "trend-" + id;
          p.repositoryUrl = "https://github.com/test/trend-" + id + ".git";
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

  private static void build(
      Long projectId,
      Build.Status status,
      Build.Trigger trigger,
      String branch,
      String revision,
      Instant requestedAt,
      Instant startedAt,
      Instant finishedAt) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Build b = new Build();
          b.projectId = projectId;
          b.number = Build.nextNumber(projectId);
          b.requestedBy = LEAD;
          b.status = status;
          b.trigger = trigger;
          b.branch = branch;
          b.revision = revision;
          b.requestedAt = requestedAt;
          b.startedAt = startedAt;
          b.finishedAt = finishedAt;
          b.persist();
        });
  }

  private static void build(
      Long projectId, Build.Status status, Build.Trigger trigger, String branch, Instant at) {
    build(projectId, status, trigger, branch, "0123456789abcdef", at, at, at.plusSeconds(30));
  }

  private static JsonPath trends(String query) {
    return JsonPath.from(
            given()
                .get("/api/reports/builds/trends" + query)
                .then()
                .statusCode(200)
                .extract()
                .asString())
        .using(DOUBLES);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "trend-admin-id")})
  void bucketsAndZeroFillsDays() {
    Long p = project(LEAD);
    Instant t1 = Instant.parse("2026-01-01T10:00:00Z");
    Instant t2 = Instant.parse("2026-01-03T10:00:00Z");
    build(p, Build.Status.PASSED, Build.Trigger.MANUAL, "main", "rev1", t1, t1, t1.plusSeconds(30));
    build(p, Build.Status.FAILED, Build.Trigger.MANUAL, "main", "rev2", t2, t2, t2.plusSeconds(30));

    JsonPath s =
        trends("?projectId=" + p + "&from=2026-01-01T00:00:00Z&to=2026-01-03T23:59:59Z&bucket=day");
    assertEquals("day", s.getString("bucket"));
    assertEquals(3, s.getList("buckets").size());
    assertEquals("2026-01-01T00:00:00Z", s.getString("buckets[0].bucket"));
    assertEquals(1, s.getLong("buckets[0].builds"));
    assertEquals(0, s.getLong("buckets[1].builds"));
    assertEquals(1, s.getLong("buckets[2].builds"));
    assertEquals(2, s.getLong("totals.builds"));
    assertFalse(s.getBoolean("truncated"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "trend-admin-id")})
  void bucketsDefaultToDayOrWeek() {
    Long p = project(LEAD);
    build(p, Build.Status.PASSED, Build.Trigger.MANUAL, "main", Instant.now());
    assertEquals("day", trends("?projectId=" + p + "&period=7d").getString("bucket"));
    assertEquals("day", trends("?projectId=" + p + "&period=30d").getString("bucket"));
    assertEquals("week", trends("?projectId=" + p + "&period=365d").getString("bucket"));
    assertEquals("week", trends("?projectId=" + p + "&period=all").getString("bucket"));
    assertEquals(
        "day",
        trends("?projectId=" + p + "&from=2026-01-01T00:00:00Z&to=2026-02-01T00:00:00Z")
            .getString("bucket"));
    assertEquals(
        "week",
        trends("?projectId=" + p + "&from=2026-01-01T00:00:00Z&to=2026-06-01T00:00:00Z")
            .getString("bucket"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "trend-admin-id")})
  void widensDayToWeekBeforeTruncating() {
    Long p = project(LEAD);
    build(p, Build.Status.PASSED, Build.Trigger.MANUAL, "main", Instant.now());
    JsonPath s =
        trends("?projectId=" + p + "&from=2026-01-01T00:00:00Z&to=2026-06-01T00:00:00Z&bucket=day");
    assertEquals("week", s.getString("bucket"));
    assertFalse(s.getBoolean("truncated"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "trend-admin-id")})
  void capsAtOneHundredAndTwentyBucketsKeepingTheNewest() {
    Long p = project(LEAD);
    build(p, Build.Status.PASSED, Build.Trigger.MANUAL, "main", Instant.now());
    JsonPath s =
        trends(
            "?projectId=" + p + "&from=2015-01-01T00:00:00Z&to=2026-06-01T00:00:00Z&bucket=week");
    assertTrue(s.getBoolean("truncated"));
    assertEquals(120, s.getList("buckets").size());
    // The newest buckets are kept: the last one is the week containing the end of the range.
    assertTrue(s.getString("buckets[119].bucket").compareTo("2026-05-01") > 0);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "trend-admin-id")})
  void reportsQueueWaitAndDuration() {
    Long p = project(LEAD);
    Instant req = Instant.parse("2026-02-01T10:00:00Z");
    build(
        p,
        Build.Status.PASSED,
        Build.Trigger.MANUAL,
        "main",
        "r",
        req,
        req.plusSeconds(120),
        req.plusSeconds(180));
    JsonPath s = trends("?projectId=" + p + "&from=2026-02-01T00:00:00Z&to=2026-02-01T23:59:59Z");
    assertEquals(120.0, s.getDouble("totals.averageQueueSeconds"), 1e-6);
    assertEquals(60.0, s.getDouble("totals.averageDurationSeconds"), 1e-6);
    assertEquals(120.0, s.getDouble("buckets[0].averageQueueSeconds"), 1e-6);
    assertEquals(60.0, s.getDouble("buckets[0].averageDurationSeconds"), 1e-6);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "trend-admin-id")})
  void reportsDurationPercentiles() {
    Long p = project(LEAD);
    Instant base = Instant.parse("2026-03-01T10:00:00Z");
    build(
        p,
        Build.Status.PASSED,
        Build.Trigger.MANUAL,
        "main",
        "r1",
        base,
        base,
        base.plusSeconds(10));
    build(
        p,
        Build.Status.PASSED,
        Build.Trigger.MANUAL,
        "main",
        "r2",
        base,
        base,
        base.plusSeconds(20));
    build(
        p,
        Build.Status.PASSED,
        Build.Trigger.MANUAL,
        "main",
        "r3",
        base,
        base,
        base.plusSeconds(30));
    JsonPath s = trends("?projectId=" + p + "&from=2026-03-01T00:00:00Z&to=2026-03-01T23:59:59Z");
    assertEquals(20.0, s.getDouble("totals.averageDurationSeconds"), 1e-6);
    assertEquals(20.0, s.getDouble("totals.p50DurationSeconds"), 1e-6);
    assertEquals(29.0, s.getDouble("totals.p95DurationSeconds"), 1e-6);
    assertEquals(3, s.getLong("totals.passed"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "trend-admin-id")})
  void zeroFillsEachTriggerSeries() {
    Long p = project(LEAD);
    Instant d1 = Instant.parse("2026-04-01T10:00:00Z");
    Instant d2 = Instant.parse("2026-04-02T10:00:00Z");
    build(p, Build.Status.PASSED, Build.Trigger.MANUAL, "main", "r1", d1, d1, d1.plusSeconds(1));
    build(p, Build.Status.FAILED, Build.Trigger.POLL, "main", "r2", d2, d2, d2.plusSeconds(1));
    JsonPath s =
        trends("?projectId=" + p + "&from=2026-04-01T00:00:00Z&to=2026-04-02T23:59:59Z&bucket=day");
    assertEquals(List.of("MANUAL", "POLL"), s.getList("byTrigger.trigger"));
    assertEquals(2, s.getList("byTrigger[0].buckets").size());
    assertEquals(1, s.getLong("byTrigger[0].buckets[0].builds"));
    assertEquals(0, s.getLong("byTrigger[0].buckets[1].builds"));
    assertEquals(0, s.getLong("byTrigger[1].buckets[0].builds"));
    assertEquals(1, s.getLong("byTrigger[1].buckets[1].builds"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "trend-admin-id")})
  void bucketsDurationsIntoAHistogram() {
    Long p = project(LEAD);
    Instant b = Instant.parse("2026-05-01T10:00:00Z");
    build(p, Build.Status.PASSED, Build.Trigger.MANUAL, "main", "r1", b, b, b.plusSeconds(30));
    build(p, Build.Status.PASSED, Build.Trigger.MANUAL, "main", "r2", b, b, b.plusSeconds(120));
    build(p, Build.Status.PASSED, Build.Trigger.MANUAL, "main", "r3", b, b, b.plusSeconds(400));
    JsonPath s = trends("?projectId=" + p + "&from=2026-05-01T00:00:00Z&to=2026-05-01T23:59:59Z");
    assertEquals(
        List.of("< 1m", "1-5m", "5-15m", "15-60m", "1-6h", "> 6h"), s.getList("histogram.label"));
    assertEquals(1, s.getLong("histogram[0].builds"));
    assertEquals(1, s.getLong("histogram[1].builds"));
    assertEquals(1, s.getLong("histogram[2].builds"));
    assertEquals(0, s.getLong("histogram[3].builds"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "trend-admin-id")})
  void ranksSlowestBranchesAndProjects() {
    Long p = project(LEAD);
    Instant b = Instant.parse("2026-06-01T10:00:00Z");
    build(p, Build.Status.PASSED, Build.Trigger.MANUAL, "slow", "r1", b, b, b.plusSeconds(600));
    build(p, Build.Status.PASSED, Build.Trigger.MANUAL, "fast", "r2", b, b, b.plusSeconds(60));
    JsonPath s = trends("?projectId=" + p + "&period=all");
    assertEquals("slow", s.getString("slowestBranches[0].label"));
    assertEquals("fast", s.getString("slowestBranches[1].label"));
    assertEquals(600.0, s.getDouble("slowestBranches[0].averageSeconds"), 1e-6);
    assertEquals(1, s.getList("slowestProjects").size());
    assertEquals(330.0, s.getDouble("slowestProjects[0].averageSeconds"), 1e-6);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "trend-admin-id")})
  void findsRevisionsThatFailedThenPassed() {
    Long p = project(LEAD);
    Instant b = Instant.parse("2026-07-01T10:00:00Z");
    Instant later = b.plusSeconds(60);
    build(p, Build.Status.FAILED, Build.Trigger.MANUAL, "main", "flaky1", b, b, b.plusSeconds(1));
    build(
        p,
        Build.Status.PASSED,
        Build.Trigger.MANUAL,
        "main",
        "flaky1",
        later,
        later,
        later.plusSeconds(1));
    build(p, Build.Status.PASSED, Build.Trigger.MANUAL, "main", "reverted", b, b, b.plusSeconds(1));
    build(
        p,
        Build.Status.FAILED,
        Build.Trigger.MANUAL,
        "main",
        "reverted",
        later,
        later,
        later.plusSeconds(1));
    build(p, Build.Status.FAILED, Build.Trigger.MANUAL, "main", "broken", b, b, b.plusSeconds(1));
    JsonPath s = trends("?projectId=" + p + "&period=all");
    assertEquals(List.of("flaky1"), s.getList("flakyRevisions.revision"));
    assertEquals(1, s.getLong("flakyRevisions[0].failures"));
    assertEquals(1, s.getLong("flakyRevisions[0].passes"));
    assertEquals("main", s.getString("flakyRevisions[0].branch"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "trend-admin-id")})
  void projectAndPeriodFiltersRestrictTheReport() {
    Long a = project(LEAD), b = project(LEAD);
    Instant recent = Instant.now().minus(1, ChronoUnit.DAYS);
    Instant old = Instant.now().minus(60, ChronoUnit.DAYS);
    build(
        a,
        Build.Status.PASSED,
        Build.Trigger.MANUAL,
        "main",
        "r",
        recent,
        recent,
        recent.plusSeconds(1));
    build(a, Build.Status.PASSED, Build.Trigger.MANUAL, "main", "r2", old, old, old.plusSeconds(1));
    build(
        b,
        Build.Status.PASSED,
        Build.Trigger.MANUAL,
        "main",
        "r",
        recent,
        recent,
        recent.plusSeconds(1));
    assertEquals(1, trends("?projectId=" + a + "&period=30d").getLong("totals.builds"));
    assertEquals(2, trends("?projectId=" + a + "&period=all").getLong("totals.builds"));
    assertEquals(1, trends("?projectId=" + b + "&period=all").getLong("totals.builds"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "trend-admin-id")})
  void rejectsInvalidInput() {
    given().get("/api/reports/builds/trends?from=nope").then().statusCode(400);
    given().get("/api/reports/builds/trends?to=2026-13-01T00:00:00Z").then().statusCode(400);
    given()
        .get("/api/reports/builds/trends?from=2026-02-01T00:00:00Z&to=2026-01-01T00:00:00Z")
        .then()
        .statusCode(400);
    given().get("/api/reports/builds/trends?bucket=hour").then().statusCode(400);
    given().get("/api/reports/builds/trends?period=week").then().statusCode(400);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "trend-admin-id")})
  void explainsRejectedInput() {
    String reversed =
        given()
            .get("/api/reports/builds/trends?from=2026-02-01T00:00:00Z&to=2026-01-01T00:00:00Z")
            .then()
            .statusCode(400)
            .extract()
            .asString();
    assertTrue(reversed.contains("From must not be after To"), reversed);

    String invalid =
        given()
            .get("/api/reports/builds/trends?from=nope")
            .then()
            .statusCode(400)
            .extract()
            .asString();
    assertTrue(invalid.contains("ISO-8601"), invalid);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "trend-admin-id")})
  void keepsNullMetricsForEmptyBuckets() {
    Long p = project(LEAD);
    Instant t1 = Instant.parse("2026-07-01T10:00:00Z");
    Instant t3 = Instant.parse("2026-07-03T10:00:00Z");
    build(p, Build.Status.PASSED, Build.Trigger.MANUAL, "main", "r1", t1, t1, t1.plusSeconds(30));
    build(p, Build.Status.FAILED, Build.Trigger.MANUAL, "main", "r3", t3, t3, t3.plusSeconds(30));

    JsonPath s =
        trends("?projectId=" + p + "&from=2026-07-01T00:00:00Z&to=2026-07-03T23:59:59Z&bucket=day");
    // An empty bucket reports that nothing happened rather than 0% and 0 seconds.
    assertEquals(0, s.getLong("buckets[1].builds"));
    assertNull(s.get("buckets[1].successRate"));
    assertNull(s.get("buckets[1].averageDurationSeconds"));
    assertNull(s.get("buckets[1].p50DurationSeconds"));
    assertNull(s.get("buckets[1].p95DurationSeconds"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = LEAD)})
  void leadSeesOnlyTheProjectsTheyLead() {
    Long led = project(LEAD), other = project("someone-else");
    build(other, Build.Status.FAILED, Build.Trigger.MANUAL, "main", Instant.now());
    List<Long> ids =
        trends("").getList("projects.id", Double.class).stream().map(Double::longValue).toList();
    assertTrue(ids.contains(led));
    assertFalse(ids.contains(other));
    given().get("/api/reports/builds/trends?projectId=" + other).then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "trend-member-id")})
  void membersCannotSeeTrends() {
    given().get("/api/reports/builds/trends").then().statusCode(403);
  }

  @Test
  void anonymousCannotSeeTrends() {
    given().get("/api/reports/builds/trends").then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "trend-admin-id")})
  void pdfContainsTheTrendTables() throws Exception {
    Long p = project(LEAD);
    Instant at = Instant.now();
    build(p, Build.Status.FAILED, Build.Trigger.MANUAL, "main", "r", at, at, at.plusSeconds(30));
    byte[] pdf =
        given()
            .get("/api/reports/builds/pdf?projectId=" + p)
            .then()
            .statusCode(200)
            .extract()
            .asByteArray();
    PdfReader reader = new PdfReader(pdf);
    StringBuilder text = new StringBuilder();
    for (int page = 1; page <= reader.getNumberOfPages(); page++) {
      text.append(new PdfTextExtractor(reader).getTextFromPage(page));
    }
    reader.close();
    assertTrue(text.toString().contains("Trends"));
    assertTrue(text.toString().contains("Volume by bucket"));
    assertTrue(text.toString().contains("Flaky revisions"));
  }
}
