/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS
 *   ECLIPSE PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THE AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.restassured.http.ContentType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(NoSecretKeyProfile.class)
class TeamCredentialMissingKeyTest {
  private static Long team() {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Team team = new Team();
          team.name = "no-key-team-" + UUID.randomUUID();
          team.ownerSubject = "lead-id";
          team.persist();
          addMember(team.id, "lead-id", ProjectMember.TeamRole.LEAD);
          addMember(team.id, "member-id", ProjectMember.TeamRole.MEMBER);
          return team.id;
        });
  }

  private static void addMember(Long teamId, String subject, ProjectMember.TeamRole role) {
    TeamMember member = new TeamMember();
    member.teamId = teamId;
    member.subject = subject;
    member.role = role;
    member.createdAt = Instant.now();
    member.persist();
  }

  private static Long project(Long teamId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project project = new Project();
          project.name = "no-key-project-" + UUID.randomUUID();
          project.repositoryUrl = "https://github.com/octocat/no-key-" + UUID.randomUUID();
          project.ownerSubject = "lead-id";
          project.privateRepository = true;
          project.teamId = teamId;
          project.persist();
          return project.id;
        });
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void settingATeamTokenFailsClosedWithoutAKey() {
    Long id = team();
    Long projectId = project(id);
    TestGitRepositoryService.resetCalls();
    given()
        .contentType(ContentType.JSON)
        .body("{\"token\":\"never-stored\",\"projectId\":" + projectId + "}")
        .put("/api/teams/" + id + "/token")
        .then()
        .statusCode(503)
        .body("error", equalTo("Team credential encryption is not configured"));
    assertNull(QuarkusTransaction.requiringNew().call(() -> Team.<Team>findById(id)).tokenSecret);
    org.junit.jupiter.api.Assertions.assertEquals(0, TestGitRepositoryService.calls());
    given()
        .delete("/api/teams/" + id + "/token")
        .then()
        .statusCode(503)
        .body("error", equalTo("Team credential encryption is not configured"));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void aTeamMemberCannotProbeWhetherEncryptionIsConfigured() {
    Long id = team();
    // Authorization is decided before any key or payload check, so this is 403, never 503 or 400.
    given()
        .contentType(ContentType.JSON)
        .body("{\"token\":\"never-stored\"}")
        .put("/api/teams/" + id + "/token")
        .then()
        .statusCode(403);
    given()
        .contentType(ContentType.JSON)
        .body("{\"token\":\"" + "z".repeat(TeamResource.MAX_TOKEN_LENGTH + 1) + "\"}")
        .put("/api/teams/" + id + "/token")
        .then()
        .statusCode(403);
    given().delete("/api/teams/" + id + "/token").then().statusCode(403);
    assertNull(QuarkusTransaction.requiringNew().call(() -> Team.<Team>findById(id)).tokenSecret);
  }

  @Test
  @TestSecurity(user = "outsider", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "outsider-id"))
  void aNonMemberSeesNotFoundRatherThanEncryptionConfiguration() {
    Long id = team();
    given()
        .contentType(ContentType.JSON)
        .body("{\"token\":\"never-stored\"}")
        .put("/api/teams/" + id + "/token")
        .then()
        .statusCode(404);
    given().delete("/api/teams/" + id + "/token").then().statusCode(404);
  }
}
