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
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(BranchTestProfile.class)
class BranchResourceTest {
  private static final String GITHUB = "https://github.com/octocat/Hello-World";

  private static String github() {
    return GITHUB + "-" + java.util.UUID.randomUUID() + ".git";
  }

  private static String unreachable() {
    return "https://github.com/octocat/unreachable-" + java.util.UUID.randomUUID() + ".git";
  }

  private Long project(boolean priv, String repositoryUrl) {
    return project(priv, repositoryUrl, null);
  }

  private Long project(boolean priv, String repositoryUrl, String credentialRef) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project p = new Project();
          p.name = "example-" + java.util.UUID.randomUUID();
          p.repositoryUrl = repositoryUrl;
          p.ownerSubject = "owner-id";
          p.privateRepository = priv;
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

  @Test
  void anonymousIsRejected() {
    Long id = project(false, github());
    given().get("/api/projects/" + id + "/branches").then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "outsider", roles = "outsider")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void unrelatedRoleIsForbidden() {
    Long id = project(false, github());
    given().get("/api/projects/" + id + "/branches").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void memberCanListBranchesOfPublicProject() {
    Long id = project(false, github());
    TestGitRepositoryService.resetCalls();
    given()
        .get("/api/projects/" + id + "/branches")
        .then()
        .statusCode(200)
        .body("branches", hasSize(2))
        .body("branches", contains("dev", "main"))
        .body("reason", nullValue());
    assertEquals(1, TestGitRepositoryService.calls());
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void privateProjectIsHidden() {
    Long id = project(true, github());
    TestGitRepositoryService.resetCalls();
    given().get("/api/projects/" + id + "/branches").then().statusCode(404);
    assertEquals(0, TestGitRepositoryService.calls());
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
  void ownerGetsPrivateRepositoryReasonWithoutRemoteCall() {
    Long id = project(true, github());
    TestGitRepositoryService.resetCalls();
    given()
        .get("/api/projects/" + id + "/branches")
        .then()
        .statusCode(200)
        .body("branches", hasSize(0))
        .body("reason", equalTo("private_repository"));
    assertEquals(0, TestGitRepositoryService.calls());
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminGetsPrivateRepositoryReasonWithoutRemoteCall() {
    Long id = project(true, github());
    TestGitRepositoryService.resetCalls();
    given()
        .get("/api/projects/" + id + "/branches")
        .then()
        .statusCode(200)
        .body("branches", hasSize(0))
        .body("reason", equalTo("private_repository"));
    assertEquals(0, TestGitRepositoryService.calls());
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
  void ownerWithCredentialGetsRemoteBranches() {
    Long id = project(true, github(), "CISYS_GIT_TEST");
    TestGitRepositoryService.resetCalls();
    given()
        .get("/api/projects/" + id + "/branches")
        .then()
        .statusCode(200)
        .body("branches", hasSize(2))
        .body("reason", nullValue());
    assertEquals(1, TestGitRepositoryService.calls());
    assertEquals("test-token", TestGitRepositoryService.credential());
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void missingCredentialIsReportedAsBadGateway() {
    Long id = project(true, github(), "CISYS_GIT_MISSING");
    TestGitRepositoryService.resetCalls();
    given()
        .get("/api/projects/" + id + "/branches")
        .then()
        .statusCode(502)
        .body("error", equalTo("Missing Git credential"));
    assertEquals(0, TestGitRepositoryService.calls(), "the token is resolved before the network");
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void unsupportedRepositoryUrlIsRejected() {
    Long id = project(false, "http://example.com/octocat/Hello-World.git");
    given()
        .get("/api/projects/" + id + "/branches")
        .then()
        .statusCode(400)
        .body("error", equalTo("Use an HTTPS repository URL without credentials"));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void repositoryFailureIsReportedAsBadGateway() {
    Long id = project(false, unreachable());
    given()
        .get("/api/projects/" + id + "/branches")
        .then()
        .statusCode(502)
        .body("error", equalTo("Unable to read branches from the repository"));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void unknownProjectIsNotFound() {
    given().get("/api/projects/999999/branches").then().statusCode(404);
  }
}
