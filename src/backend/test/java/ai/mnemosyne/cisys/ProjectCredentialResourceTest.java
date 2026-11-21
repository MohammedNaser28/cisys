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
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.UserTransaction;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(BranchTestProfile.class)
class ProjectCredentialResourceTest {
  @Inject CredentialCipher cipher;
  @Inject EntityManager entityManager;
  @Inject UserTransaction userTransaction;

  private static Long project(String owner, boolean privateRepository, String url) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project p = new Project();
          String name = "credential-" + UUID.randomUUID();
          p.name = name;
          p.repositoryUrl = url;
          p.ownerSubject = owner;
          p.privateRepository = privateRepository;
          p.persist();
          ProjectMember lead = new ProjectMember();
          lead.projectId = p.id;
          lead.subject = owner;
          lead.role = ProjectMember.TeamRole.LEAD;
          lead.createdAt = Instant.now();
          lead.persist();
          return p.id;
        });
  }

  private static Long project(String owner, boolean privateRepository) {
    return project(owner, privateRepository, "https://github.com/octocat/" + UUID.randomUUID());
  }

  private static void member(Long projectId, String subject) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          ProjectMember m = new ProjectMember();
          m.projectId = projectId;
          m.subject = subject;
          m.role = ProjectMember.TeamRole.MEMBER;
          m.createdAt = Instant.now();
          m.persist();
        });
  }

  private static Project row(Long id) {
    return QuarkusTransaction.requiringNew().call(() -> Project.<Project>findById(id));
  }

  /** A group with the given LEAD and MEMBER, linked to the project with {@code linkRole}. */
  private static void groupLinkedTo(
      Long projectId, ProjectMember.TeamRole linkRole, String lead, String member) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          UserGroup group = new UserGroup();
          group.name = "group-" + UUID.randomUUID();
          group.createdAt = Instant.now();
          group.persistAndFlush();
          groupMember(group.id, lead, GroupMember.GroupRole.LEAD);
          groupMember(group.id, member, GroupMember.GroupRole.MEMBER);
          GroupProject link = new GroupProject();
          link.groupId = group.id;
          link.projectId = projectId;
          link.role = linkRole;
          link.persistAndFlush();
        });
  }

  private static void groupMember(Long groupId, String subject, GroupMember.GroupRole role) {
    GroupMember m = new GroupMember();
    m.groupId = groupId;
    m.subject = subject;
    m.role = role;
    m.createdAt = Instant.now();
    m.persist();
  }

  private static String tokenBody(String token) {
    return "{\"token\":\"" + token + "\"}";
  }

  private static String projectBody(Long id, String name, String url, boolean privateRepository) {
    return "{\"name\":\""
        + name
        + "\",\"repositoryUrl\":\""
        + url
        + "\",\"privateRepository\":"
        + privateRepository
        + "}";
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void leadSetsRotatesAndClearsTheToken() {
    Long id = project("lead-id", true);
    String first = "s3cr3t-" + UUID.randomUUID();
    String firstBody =
        given()
            .contentType(ContentType.JSON)
            .body(tokenBody(first))
            .put("/api/projects/" + id + "/credential/token")
            .then()
            .statusCode(200)
            .body("credentialSecretSet", equalTo(true))
            .body("credentialUpdatedAt", not(nullValue()))
            .body("$", not(org.hamcrest.Matchers.hasKey("credentialUpdatedBy")))
            .extract()
            .asString();
    assertFalse(firstBody.contains(first), "the token must never be returned");
    Project afterFirst = row(id);
    assertNotNull(afterFirst.credentialSecret);
    assertEquals(first, cipher.decrypt(afterFirst.credentialSecret, id));
    assertEquals("lead-id", afterFirst.credentialUpdatedBy);
    assertNotNull(afterFirst.credentialUpdatedAt);

    String second = "s3cr3t-" + UUID.randomUUID();
    given()
        .contentType(ContentType.JSON)
        .body(tokenBody(second))
        .put("/api/projects/" + id + "/credential/token")
        .then()
        .statusCode(200)
        .body("credentialSecretSet", equalTo(true));
    Project afterSecond = row(id);
    assertFalse(
        java.util.Arrays.equals(afterFirst.credentialSecret, afterSecond.credentialSecret),
        "rotation must produce a fresh ciphertext");
    assertEquals(second, cipher.decrypt(afterSecond.credentialSecret, id));

    given()
        .delete("/api/projects/" + id + "/credential/token")
        .then()
        .statusCode(200)
        .body("credentialSecretSet", equalTo(false))
        .body("credentialUpdatedAt", nullValue());
    Project cleared = row(id);
    assertNull(cleared.credentialSecret);
    assertNull(cleared.credentialUpdatedAt);
    assertNull(cleared.credentialUpdatedBy);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminSetsATokenAndSeesWhoSetIt() {
    Long id = project("other-id", true);
    given()
        .contentType(ContentType.JSON)
        .body(tokenBody("admin-token"))
        .put("/api/projects/" + id + "/credential/token")
        .then()
        .statusCode(200)
        .body("credentialSecretSet", equalTo(true))
        .body("credentialUpdatedBy", equalTo("admin-id"));
    assertEquals("admin-token", cipher.decrypt(row(id).credentialSecret, id));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void memberCannotSetOrClearTheToken() {
    Long id = project("lead-id", true);
    member(id, "member-id");
    given()
        .contentType(ContentType.JSON)
        .body(tokenBody("member-token"))
        .put("/api/projects/" + id + "/credential/token")
        .then()
        .statusCode(403);
    given().delete("/api/projects/" + id + "/credential/token").then().statusCode(403);
    assertNull(row(id).credentialSecret);
  }

  @Test
  @TestSecurity(user = "group-lead", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "group-lead-id"))
  void groupLinkedLeadWithoutADirectRowSetsAndClearsTheToken() {
    Long id = project("lead-id", true);
    groupLinkedTo(id, ProjectMember.TeamRole.LEAD, "group-lead-id", "group-member-id");
    String token = "group-" + UUID.randomUUID();
    given()
        .contentType(ContentType.JSON)
        .body(tokenBody(token))
        .put("/api/projects/" + id + "/credential/token")
        .then()
        .statusCode(200)
        .body("credentialSecretSet", equalTo(true))
        .body("$", not(org.hamcrest.Matchers.hasKey("credentialUpdatedBy")));
    assertEquals(token, cipher.decrypt(row(id).credentialSecret, id));
    assertEquals(
        0,
        QuarkusTransaction.requiringNew()
            .call(
                () -> ProjectMember.count("projectId = ?1 and subject = ?2", id, "group-lead-id")),
        "the LEAD role must come from the group link only");
    given()
        .delete("/api/projects/" + id + "/credential/token")
        .then()
        .statusCode(200)
        .body("credentialSecretSet", equalTo(false));
    assertNull(row(id).credentialSecret);
  }

  @Test
  @TestSecurity(user = "group-member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "group-member-id"))
  void groupLinkedMemberCannotSetOrClearTheToken() {
    Long id = project("lead-id", true);
    // The link role is the project role every group member gets, whatever their group role.
    groupLinkedTo(id, ProjectMember.TeamRole.MEMBER, "group-lead-id", "group-member-id");
    given()
        .contentType(ContentType.JSON)
        .body(tokenBody("group-member-token"))
        .put("/api/projects/" + id + "/credential/token")
        .then()
        .statusCode(403);
    given().delete("/api/projects/" + id + "/credential/token").then().statusCode(403);
    assertNull(row(id).credentialSecret);
  }

  @Test
  @TestSecurity(user = "outsider", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "outsider-id"))
  void nonMemberOfAPrivateProjectGetsNotFound() {
    Long id = project("lead-id", true);
    given()
        .contentType(ContentType.JSON)
        .body(tokenBody("outsider-token"))
        .put("/api/projects/" + id + "/credential/token")
        .then()
        .statusCode(404);
    given().delete("/api/projects/" + id + "/credential/token").then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void invalidTokenIsRejectedAndNothingIsStored() {
    Long id =
        project("lead-id", true, "https://github.com/octocat/unreachable-" + UUID.randomUUID());
    given()
        .contentType(ContentType.JSON)
        .body(tokenBody("rejected-token"))
        .put("/api/projects/" + id + "/credential/token")
        .then()
        .statusCode(400)
        .body("error", equalTo("The token could not access the repository"));
    Project p = row(id);
    assertNull(p.credentialSecret);
    assertNull(p.credentialUpdatedAt);
    assertNull(p.credentialUpdatedBy);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void blankTokenIsRejected() {
    Long id = project("lead-id", true);
    given()
        .contentType(ContentType.JSON)
        .body("{\"token\":\"  \"}")
        .put("/api/projects/" + id + "/credential/token")
        .then()
        .statusCode(400)
        .body("error", equalTo("token is required"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void publicProjectCannotStoreAToken() {
    Long id = project("lead-id", false);
    given()
        .contentType(ContentType.JSON)
        .body(tokenBody("public-token"))
        .put("/api/projects/" + id + "/credential/token")
        .then()
        .statusCode(400)
        .body("error", equalTo("Only private projects can use a credential"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void tokenValidationUsesTheRepositoryHostRules() {
    Long id = project("lead-id", true, "https://example.com/octocat/repo");
    given()
        .contentType(ContentType.JSON)
        .body(tokenBody("host-token"))
        .put("/api/projects/" + id + "/credential/token")
        .then()
        .statusCode(400);
    assertNull(row(id).credentialSecret);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void changingTheRepositoryUrlClearsTheToken() {
    Long id = project("lead-id", true);
    given()
        .contentType(ContentType.JSON)
        .body(tokenBody("url-token"))
        .put("/api/projects/" + id + "/credential/token")
        .then()
        .statusCode(200);
    Project before = row(id);
    given()
        .contentType(ContentType.JSON)
        .body(
            projectBody(
                id, before.name, "https://github.com/octocat/moved-" + UUID.randomUUID(), true))
        .put("/api/projects/" + id)
        .then()
        .statusCode(200)
        .body("credentialSecretSet", equalTo(false))
        .body("credentialUpdatedAt", nullValue());
    Project after = row(id);
    assertNull(after.credentialSecret);
    assertNull(after.credentialUpdatedAt);
    assertNull(after.credentialUpdatedBy);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void leadCannotMakeAProjectPublicAndNothingChanges() {
    Long id = project("lead-id", true);
    given()
        .contentType(ContentType.JSON)
        .body(tokenBody("kept-token"))
        .put("/api/projects/" + id + "/credential/token")
        .then()
        .statusCode(200);
    Project before = row(id);
    given()
        .contentType(ContentType.JSON)
        .body(projectBody(id, before.name, before.repositoryUrl, false))
        .put("/api/projects/" + id)
        .then()
        .statusCode(403);
    Project after = row(id);
    assertTrue(after.privateRepository);
    assertNotNull(after.credentialSecret);
    assertEquals(before.name, after.name);
    assertEquals(before.repositoryUrl, after.repositoryUrl);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void memberCannotMakeAPrivateProjectPublic() {
    Long id = project("lead-id", true);
    member(id, "member-id");
    Project before = row(id);
    given()
        .contentType(ContentType.JSON)
        .body(projectBody(id, before.name, before.repositoryUrl, false))
        .put("/api/projects/" + id)
        .then()
        .statusCode(403);
    Project after = row(id);
    assertTrue(after.privateRepository);
    assertEquals(before.name, after.name);
    assertEquals(before.repositoryUrl, after.repositoryUrl);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminMakesAProjectPublicAndClearsTheToken() {
    Long id = project("lead-id", true);
    given()
        .contentType(ContentType.JSON)
        .body(tokenBody("public-bound-token"))
        .put("/api/projects/" + id + "/credential/token")
        .then()
        .statusCode(200);
    Project before = row(id);
    given()
        .contentType(ContentType.JSON)
        .body(projectBody(id, before.name, before.repositoryUrl, false))
        .put("/api/projects/" + id)
        .then()
        .statusCode(200)
        .body("privateRepository", equalTo(false))
        .body("credentialSecretSet", equalTo(false));
    Project after = row(id);
    assertFalse(after.privateRepository);
    assertNull(after.credentialSecret);
    assertNull(after.credentialUpdatedAt);
    assertNull(after.credentialUpdatedBy);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void concurrentPublicChangeAndTokenWriteNeverLeavesASecretOnAPublicProject() throws Exception {
    Long id = project("lead-id", true);
    Project before = row(id);
    CountDownLatch started = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    userTransaction.begin();
    try {
      entityManager.find(Project.class, id, LockModeType.PESSIMISTIC_WRITE);
      Future<Response> publicChange =
          executor.submit(() -> {
            started.countDown();
            return given()
                .contentType(ContentType.JSON)
                .body(projectBody(id, before.name, before.repositoryUrl, false))
                .put("/api/projects/" + id);
          });
      assertTrue(started.await(5, TimeUnit.SECONDS));
      Thread.sleep(250);
      Future<Response> tokenWrite =
          executor.submit(() ->
              given()
                  .contentType(ContentType.JSON)
                  .body(tokenBody("concurrent-token"))
                  .put("/api/projects/" + id + "/credential/token"));
      Thread.sleep(500);
      userTransaction.commit();

      Response publicResponse = publicChange.get(5, TimeUnit.SECONDS);
      Response tokenResponse = tokenWrite.get(5, TimeUnit.SECONDS);
      assertEquals(200, publicResponse.getStatusCode());
      assertTrue(tokenResponse.getStatusCode() >= 200 && tokenResponse.getStatusCode() < 500);
    } finally {
      if (userTransaction.getStatus() == jakarta.transaction.Status.STATUS_ACTIVE) {
        userTransaction.rollback();
      }
      executor.shutdownNow();
    }

    Project after = row(id);
    assertFalse(after.privateRepository);
    assertNull(after.credentialSecret);
    assertNull(after.credentialUpdatedAt);
    assertNull(after.credentialUpdatedBy);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void leadMayMakeAPublicProjectPrivate() {
    Long id = project("lead-id", false);
    Project before = row(id);
    given()
        .contentType(ContentType.JSON)
        .body(projectBody(id, before.name, before.repositoryUrl, true))
        .put("/api/projects/" + id)
        .then()
        .statusCode(200)
        .body("privateRepository", equalTo(true));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void theTokenNeverAppearsInProjectPageOrBuildResponses() {
    Long id = project("lead-id", true);
    String token = "leak-check-" + UUID.randomUUID();
    given()
        .contentType(ContentType.JSON)
        .body(tokenBody(token))
        .put("/api/projects/" + id + "/credential/token")
        .then()
        .statusCode(200);
    Project p = row(id);
    Long buildId =
        QuarkusTransaction.requiringNew()
            .call(() -> {
              Build b = new Build();
              b.projectId = id;
              b.number = 1L;
              b.status = Build.Status.PASSED;
              b.trigger = Build.Trigger.MANUAL;
              b.requestedAt = Instant.now();
              b.repositoryUrl = p.repositoryUrl;
              b.branch = "main";
              b.credentialRef = ProjectCredentials.STORED_SECRET;
              b.persist();
              return b.id;
            });

    String list = given().get("/api/projects").then().statusCode(200).extract().asString();
    assertFalse(list.contains(token));
    String page =
        given().get("/api/project-pages/" + p.name).then().statusCode(200).extract().asString();
    assertFalse(page.contains(token));
    String builds =
        given().get("/api/projects/" + id + "/builds").then().statusCode(200).extract().asString();
    assertFalse(builds.contains(token));
    String detail =
        given()
            .get("/api/projects/" + id + "/builds/" + buildId)
            .then()
            .statusCode(200)
            .extract()
            .asString();
    assertFalse(detail.contains(token));
    String update =
        given()
            .contentType(ContentType.JSON)
            .body(projectBody(id, p.name, p.repositoryUrl, true))
            .put("/api/projects/" + id)
            .then()
            .statusCode(200)
            .extract()
            .asString();
    assertFalse(update.contains(token));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void buildResponsesHideTheInternalSecretSentinel() {
    Long id = project("lead-id", true);
    Long buildId =
        QuarkusTransaction.requiringNew()
            .call(() -> {
              Build b = new Build();
              b.projectId = id;
              b.number = 1L;
              b.status = Build.Status.PASSED;
              b.trigger = Build.Trigger.MANUAL;
              b.requestedAt = Instant.now();
              b.branch = "main";
              b.credentialRef = ProjectCredentials.STORED_SECRET;
              b.persist();
              return b.id;
            });
    given()
        .get("/api/projects/" + id + "/builds/" + buildId)
        .then()
        .statusCode(200)
        .body("$", not(org.hamcrest.Matchers.hasKey("credentialRef")));
  }
}
