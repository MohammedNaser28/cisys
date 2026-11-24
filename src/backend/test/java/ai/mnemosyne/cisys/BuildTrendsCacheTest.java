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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.restassured.path.json.JsonPath;
import io.restassured.path.json.config.JsonPathConfig;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(TrendsCacheTestProfile.class)
class BuildTrendsCacheTest {
  private static final String LEAD = "trend-cache-lead-id";
  private static final JsonPathConfig DOUBLES =
      new JsonPathConfig(JsonPathConfig.NumberReturnType.DOUBLE);

  private static Long project(String lead) {
    String id = UUID.randomUUID().toString();
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project p = new Project();
          p.name = "trend-cache-" + id;
          p.repositoryUrl = "https://github.com/test/trend-cache-" + id + ".git";
          p.ownerSubject = lead;
          p.persist();
          ProjectMember m = new ProjectMember();
          m.projectId = p.id;
          m.subject = lead;
          m.role = ProjectMember.TeamRole.LEAD;
          m.createdAt = Instant.now();
          m.persist();
          return p.id;
        });
  }

  private static void build(Long projectId, Instant at) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Build b = new Build();
          b.projectId = projectId;
          b.number = Build.nextNumber(projectId);
          b.requestedBy = LEAD;
          b.status = Build.Status.PASSED;
          b.trigger = Build.Trigger.MANUAL;
          b.branch = "main";
          b.revision = "0123456789abcdef";
          b.requestedAt = at;
          b.startedAt = at;
          b.finishedAt = at.plusSeconds(30);
          b.persist();
        });
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
  @JwtSecurity(claims = {@Claim(key = "sub", value = "trend-cache-admin-id")})
  void cachesByScopeAndRecomputesForOtherInputs() {
    Long p = project(LEAD);
    Instant at = Instant.now().minusSeconds(3600);
    build(p, at);

    JsonPath first = trends("?projectId=" + p + "&period=all");
    long cachedBuilds = first.getLong("totals.builds");
    String generatedAt = first.getString("generatedAt");

    // A new build does not change the same request while the cache entry is alive.
    build(p, at.plusSeconds(60));
    JsonPath second = trends("?projectId=" + p + "&period=all");
    assertEquals(cachedBuilds, second.getLong("totals.builds"));
    assertEquals(generatedAt, second.getString("generatedAt"));

    // A different key is computed fresh and sees the second build.
    JsonPath other = trends("?projectId=" + p + "&period=all&bucket=week");
    assertEquals(cachedBuilds + 1, other.getLong("totals.builds"));
    assertNotEquals(generatedAt, other.getString("generatedAt"));
  }
}
