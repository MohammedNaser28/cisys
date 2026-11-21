/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
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
class ProjectCredentialMissingKeyTest {
  private static Long project() {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project p = new Project();
          p.name = "no-key-" + UUID.randomUUID();
          p.repositoryUrl = "https://github.com/octocat/no-key-" + UUID.randomUUID();
          p.ownerSubject = "lead-id";
          p.privateRepository = true;
          p.persist();
          ProjectMember lead = new ProjectMember();
          lead.projectId = p.id;
          lead.subject = "lead-id";
          lead.role = ProjectMember.TeamRole.LEAD;
          lead.createdAt = Instant.now();
          lead.persist();
          return p.id;
        });
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void settingATokenFailsClosedWithoutAKey() {
    Long id = project();
    given()
        .contentType(ContentType.JSON)
        .body("{\"token\":\"never-stored\"}")
        .put("/api/projects/" + id + "/credential/token")
        .then()
        .statusCode(503)
        .body("error", equalTo("Project credential encryption is not configured"));
    assertNull(
        QuarkusTransaction.requiringNew()
            .call(() -> Project.<Project>findById(id))
            .credentialSecret);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void theCredentialReferencePathStillWorksWithoutAKey() {
    Long id = project();
    given()
        .contentType(ContentType.JSON)
        .body("{\"credentialRef\":\"CISYS_GIT_TEST\"}")
        .put("/api/projects/" + id + "/credential")
        .then()
        .statusCode(200)
        .body("credentialRef", equalTo("CISYS_GIT_TEST"))
        .body("credentialSecretSet", equalTo(false));
  }
}
