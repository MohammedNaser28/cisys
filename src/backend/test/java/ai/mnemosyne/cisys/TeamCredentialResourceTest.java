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
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(BranchTestProfile.class)
class TeamCredentialResourceTest {
  @Inject CredentialCipher cipher;

  private static Long team(String owner, String member) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Team team = new Team();
          team.name = "token-team-" + UUID.randomUUID();
          team.ownerSubject = owner;
          team.createdAt = Instant.now();
          team.persistAndFlush();
          addMember(team.id, owner, ProjectMember.TeamRole.LEAD);
          if (member != null) {
            addMember(team.id, member, ProjectMember.TeamRole.MEMBER);
          }
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

  private static Long project(Long teamId, boolean privateRepository) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project project = new Project();
          project.name = "token-project-" + UUID.randomUUID();
          project.repositoryUrl = "https://github.com/octocat/token-" + UUID.randomUUID();
          project.ownerSubject = "lead-id";
          project.privateRepository = privateRepository;
          project.teamId = teamId;
          project.persist();
          return project.id;
        });
  }

  private static Team row(Long id) {
    return QuarkusTransaction.requiringNew().call(() -> Team.<Team>findById(id));
  }

  private static String body(String token, Long projectId) {
    return "{\"token\":\"" + token + "\",\"projectId\":" + projectId + "}";
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void leadCanSetRotateAndClearAWriteOnlyTeamToken() {
    Long id = team("lead-id", null);
    Long projectId = project(id, true);
    String first = "team-secret-" + UUID.randomUUID();

    String response =
        given()
            .contentType(ContentType.JSON)
            .body(body(first, projectId))
            .put("/api/teams/" + id + "/token")
            .then()
            .statusCode(200)
            .body("set", equalTo(true))
            .body("updatedAt", notNullValue())
            .body("updatedBy", equalTo("lead-id"))
            .body("$", not(hasKey("token")))
            .extract()
            .asString();
    assertFalse(response.contains(first));
    Team firstRow = row(id);
    assertNotNull(firstRow.tokenSecret);
    assertEquals(first, cipher.decrypt(firstRow.tokenSecret, id));
    given()
        .get("/api/teams")
        .then()
        .statusCode(200)
        .body("find { it.id == " + id + " }.tokenSet", equalTo(true))
        .body("find { it.id == " + id + " }.memberCount", equalTo(1));

    String second = "team-secret-" + UUID.randomUUID();
    given()
        .contentType(ContentType.JSON)
        .body(body(second, projectId))
        .put("/api/teams/" + id + "/token")
        .then()
        .statusCode(200)
        .body("set", equalTo(true));
    Team secondRow = row(id);
    assertFalse(java.util.Arrays.equals(firstRow.tokenSecret, secondRow.tokenSecret));
    assertEquals(second, cipher.decrypt(secondRow.tokenSecret, id));

    given()
        .delete("/api/teams/" + id + "/token")
        .then()
        .statusCode(200)
        .body("set", equalTo(false))
        .body("updatedBy", equalTo("lead-id"))
        .body("updatedAt", notNullValue());
    assertNull(row(id).tokenSecret);
    given()
        .get("/api/teams")
        .then()
        .statusCode(200)
        .body("find { it.id == " + id + " }.tokenSet", equalTo(false));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void rejectsAnOversizedTokenWithoutStoringOrEchoingIt() {
    Long id = team("lead-id", null);
    String oversized = "oversized-secret-" + "x".repeat(TeamResource.MAX_TOKEN_LENGTH);

    String response =
        given()
            .contentType(ContentType.JSON)
            .body(body(oversized, null))
            .put("/api/teams/" + id + "/token")
            .then()
            .statusCode(400)
            .body(
                "error",
                equalTo(
                    "The token must be at most " + TeamResource.MAX_TOKEN_LENGTH + " characters"))
            .extract()
            .asString();

    assertFalse(response.contains("oversized-secret"));
    assertNull(row(id).tokenSecret);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void acceptsATokenAtTheMaximumLength() {
    Long id = team("lead-id", null);
    String token = "y".repeat(TeamResource.MAX_TOKEN_LENGTH);

    given()
        .contentType(ContentType.JSON)
        .body(body(token, null))
        .put("/api/teams/" + id + "/token")
        .then()
        .statusCode(200)
        .body("set", equalTo(true));

    assertEquals(token, cipher.decrypt(row(id).tokenSecret, id));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void aTeamMemberCannotChangeTheTeamToken() {
    Long id = team("lead-id", "member-id");
    given()
        .contentType(ContentType.JSON)
        .body(body("never-stored", null))
        .put("/api/teams/" + id + "/token")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminCanSetATokenOnATeamWithoutJoiningIt() {
    Long id = team("lead-id", null);
    String secret = "admin-secret-" + UUID.randomUUID();
    given()
        .contentType(ContentType.JSON)
        .body(body(secret, null))
        .put("/api/teams/" + id + "/token")
        .then()
        .statusCode(200)
        .body("set", equalTo(true))
        .body("updatedBy", equalTo("admin-id"));
    assertEquals(secret, cipher.decrypt(row(id).tokenSecret, id));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void validationProjectMustBePrivateAndBelongToTheTeam() {
    Long id = team("lead-id", null);
    Long publicProject = project(id, false);
    given()
        .contentType(ContentType.JSON)
        .body(body("public-project-secret", publicProject))
        .put("/api/teams/" + id + "/token")
        .then()
        .statusCode(400);

    Long otherTeam = team("other-lead", null);
    Long otherProject = project(otherTeam, true);
    given()
        .contentType(ContentType.JSON)
        .body(body("wrong-team-secret", otherProject))
        .put("/api/teams/" + id + "/token")
        .then()
        .statusCode(404);
  }
}
