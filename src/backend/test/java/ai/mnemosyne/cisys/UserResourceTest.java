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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import jakarta.ws.rs.WebApplicationException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class UserResourceTest {
  @jakarta.inject.Inject ProjectMemberService memberService;

  private Long leadProject;
  private Long otherProject;

  @BeforeEach
  void setUp() {
    clean();
    TestUserDirectory.add("admin-id", "admin", "admin");
    TestUserDirectory.add("lead-id", "lead", "lead");
    TestUserDirectory.add("other-lead-id", "other", "lead");
    TestUserDirectory.add("member-id", "member", "member");
    TestUserDirectory.add("member2-id", "member2", "member");
    leadProject = project("alpha", "lead-id");
    otherProject = project("beta", "other-lead-id");
    assign("member-id", leadProject);
    assign("member2-id", otherProject);
  }

  @AfterEach
  void clean() {
    TestUserDirectory.reset();
    QuarkusTransaction.requiringNew()
        .run(() -> {
          TeamMember.deleteAll();
          Team.deleteAll();
          ProjectMember.deleteAll();
          Build.deleteAll();
          Project.deleteAll();
        });
  }

  private static Long project(String name, String owner) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project p = new Project();
          p.name = name;
          p.repositoryUrl = "https://github.com/org/" + name;
          p.privateRepository = true;
          p.ownerSubject = owner;
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

  private static void assign(String subject, Long projectId) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          ProjectMember m = new ProjectMember();
          m.projectId = projectId;
          m.subject = subject;
          m.role = ProjectMember.TeamRole.MEMBER;
          m.createdAt = java.time.Instant.now();
          m.persist();
        });
  }

  private static boolean assigned(String subject, Long projectId) {
    return QuarkusTransaction.requiringNew().call(() -> ProjectMember.isMember(projectId, subject));
  }

  private static Long team(String name, String creator) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Team team = new Team();
          team.name = name;
          team.ownerSubject = creator;
          team.createdAt = java.time.Instant.now();
          team.persist();
          TeamMember m = new TeamMember();
          m.teamId = team.id;
          m.subject = creator;
          m.role = ProjectMember.TeamRole.LEAD;
          m.createdAt = java.time.Instant.now();
          m.persist();
          return team.id;
        });
  }

  private static void teamMember(Long teamId, String subject, ProjectMember.TeamRole role) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          TeamMember m = new TeamMember();
          m.teamId = teamId;
          m.subject = subject;
          m.role = role;
          m.createdAt = java.time.Instant.now();
          m.persist();
        });
  }

  private static long teamMemberCount(Long teamId, String subject) {
    return QuarkusTransaction.requiringNew()
        .call(() -> TeamMember.count("teamId = ?1 and subject = ?2", teamId, subject));
  }

  private static ProjectMember.TeamRole teamRole(Long teamId, String subject) {
    return QuarkusTransaction.requiringNew().call(() -> TeamMember.roleOf(teamId, subject));
  }

  private static void assignRole(String subject, Long projectId, ProjectMember.TeamRole role) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          ProjectMember m = new ProjectMember();
          m.projectId = projectId;
          m.subject = subject;
          m.role = role;
          m.createdAt = java.time.Instant.now();
          m.persist();
        });
  }

  private static ProjectMember.TeamRole roleOf(String subject, Long projectId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          ProjectMember m =
              ProjectMember.<ProjectMember>find(
                      "projectId = ?1 and subject = ?2", projectId, subject)
                  .firstResult();
          return m == null ? null : m.role;
        });
  }

  private static java.time.Instant createdAtOf(String subject, Long projectId) {
    return QuarkusTransaction.requiringNew()
        .call(() ->
            ProjectMember.<ProjectMember>find("projectId = ?1 and subject = ?2", projectId, subject)
                .firstResult()
                .createdAt);
  }

  private static String body(String username, String role, Long... projects) {
    return "{\"username\":\""
        + username
        + "\",\"password\":\"Secret123\",\"role\":"
        + (role == null ? "null" : "\"" + role + "\"")
        + ",\"projectIds\":"
        + List.of(projects)
        + "}";
  }

  @Test
  void anonymousIsUnauthorized() {
    given().when().get("/api/users").then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "member-id")})
  void memberCannotManageUsers() {
    given().when().get("/api/users").then().statusCode(403);
    given()
        .contentType("application/json")
        .body(body("new", "member", leadProject))
        .when()
        .post("/api/users")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminCreatesAnyRoleAndAssignsProjects() {
    String id =
        given()
            .contentType("application/json")
            .body(body("New.Lead", "lead", otherProject))
            .when()
            .post("/api/users")
            .then()
            .statusCode(201)
            .body("username", equalTo("new.lead"), "role", equalTo("lead"))
            .body("projects[0].name", equalTo("beta"))
            .extract()
            .path("id");
    assertEquals("lead", TestUserDirectory.ROLES_BY_ID.get(id));
    assertTrue(assigned(id, otherProject));

    given()
        .contentType("application/json")
        .body(body("boss", "admin"))
        .when()
        .post("/api/users")
        .then()
        .statusCode(201)
        .body("role", equalTo("admin"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminListsAndFiltersEveryone() {
    given().when().get("/api/users").then().statusCode(200).body("total", equalTo(5));
    given()
        .when()
        .get("/api/users?role=lead")
        .then()
        .statusCode(200)
        .body("total", equalTo(2), "users.username", contains("lead", "other"));
    given()
        .when()
        .get("/api/users?project=" + otherProject)
        .then()
        .statusCode(200)
        .body("users.username", containsInAnyOrder("other", "member2"));
    given().when().get("/api/users?max=2").then().body("users.size()", equalTo(2));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminCannotRemoveTheirOwnAccess() {
    given()
        .contentType("application/json")
        .body("{\"role\":\"member\"}")
        .when()
        .put("/api/users/admin-id")
        .then()
        .statusCode(400);
    given()
        .contentType("application/json")
        .body("{\"enabled\":false}")
        .when()
        .put("/api/users/admin-id")
        .then()
        .statusCode(400);
    given().when().delete("/api/users/admin-id").then().statusCode(400);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminChangesRoleAndDeletes() {
    given()
        .contentType("application/json")
        .body("{\"role\":\"lead\",\"projectIds\":[" + otherProject + "]}")
        .when()
        .put("/api/users/member-id")
        .then()
        .statusCode(200)
        .body("role", equalTo("lead"), "projects.name", contains("beta"));
    assertFalse(assigned("member-id", leadProject));

    given().when().delete("/api/users/member-id").then().statusCode(204);
    assertFalse(TestUserDirectory.USERS.containsKey("member-id"));
    assertFalse(assigned("member-id", otherProject));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void duplicateUsernameAndRejectedPassword() {
    given()
        .contentType("application/json")
        .body(body("member", "member"))
        .when()
        .post("/api/users")
        .then()
        .statusCode(409);
    given()
        .contentType("application/json")
        .body("{\"username\":\"fresh\",\"password\":\"weak\"}")
        .when()
        .post("/api/users")
        .then()
        .statusCode(400)
        .body("error", equalTo("Password policy not met"));
    given()
        .contentType("application/json")
        .body(body("bad name", "member"))
        .when()
        .post("/api/users")
        .then()
        .statusCode(400);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void leadCreatesMembersInTheirProjectsOnly() {
    String id =
        given()
            .contentType("application/json")
            .body(body("newbie", null, leadProject))
            .when()
            .post("/api/users")
            .then()
            .statusCode(201)
            .body("role", equalTo("member"))
            .extract()
            .path("id");
    assertTrue(assigned(id, leadProject));

    given()
        .contentType("application/json")
        .body(body("boss", "lead", leadProject))
        .when()
        .post("/api/users")
        .then()
        .statusCode(403);
    given()
        .contentType("application/json")
        .body(body("intruder", "member", otherProject))
        .when()
        .post("/api/users")
        .then()
        .statusCode(403);
    given()
        .contentType("application/json")
        .body(body("loner", "member"))
        .when()
        .post("/api/users")
        .then()
        .statusCode(400);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void leadSeesTheirProjectsAndMembers() {
    given()
        .when()
        .get("/api/users/projects")
        .then()
        .statusCode(200)
        .body("name", contains("alpha"));
    given()
        .when()
        .get("/api/users")
        .then()
        .statusCode(200)
        .body(
            "users.username",
            contains("lead", "member"),
            "users[0].projects.name",
            contains("alpha"));
    // Searching finds members of other projects, without revealing those projects.
    given()
        .when()
        .get("/api/users?search=member2")
        .then()
        .statusCode(200)
        .body("users.username", contains("member2"), "users[0].projects", empty());
    given().when().get("/api/users?project=" + otherProject).then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void leadChangesOnlyTheirOwnProjectsOfMembers() {
    given()
        .contentType("application/json")
        .body("{\"projectIds\":[" + leadProject + "],\"role\":\"admin\"}")
        .when()
        .put("/api/users/member2-id")
        .then()
        .statusCode(200)
        .body("role", equalTo("member"));
    assertTrue(assigned("member2-id", leadProject));
    assertTrue(assigned("member2-id", otherProject));

    given()
        .contentType("application/json")
        .body("{\"projectIds\":[]}")
        .when()
        .put("/api/users/member2-id")
        .then()
        .statusCode(200);
    assertFalse(assigned("member2-id", leadProject));
    assertTrue(assigned("member2-id", otherProject));

    given()
        .contentType("application/json")
        .body("{\"projectIds\":[" + otherProject + "]}")
        .when()
        .put("/api/users/member-id")
        .then()
        .statusCode(403);
    given()
        .contentType("application/json")
        .body("{\"projectIds\":[" + leadProject + "]}")
        .when()
        .put("/api/users/admin-id")
        .then()
        .statusCode(404);
    given().when().delete("/api/users/member-id").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void leadResetsPasswordsOfTheirMembersOnly() {
    given()
        .contentType("application/json")
        .body("{\"password\":\"Temp1234\"}")
        .when()
        .put("/api/users/member-id/password")
        .then()
        .statusCode(204);
    assertEquals("Temp1234", TestUserDirectory.PASSWORDS.get("member-id"));
    given()
        .contentType("application/json")
        .body("{\"password\":\"Temp1234\"}")
        .when()
        .put("/api/users/member2-id/password")
        .then()
        .statusCode(404);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void leadCannotTouchUsersWithoutACisysRole() {
    TestUserDirectory.add("norole-id", "norole", null);
    assign("norole-id", leadProject);
    given()
        .when()
        .get("/api/users")
        .then()
        .body("users.find { it.username == 'norole' }.manageable", equalTo(false));
    given()
        .contentType("application/json")
        .body("{\"projectIds\":[]}")
        .when()
        .put("/api/users/norole-id")
        .then()
        .statusCode(404);
    given()
        .contentType("application/json")
        .body("{\"password\":\"Temp1234\"}")
        .when()
        .put("/api/users/norole-id/password")
        .then()
        .statusCode(404);
    assertFalse(TestUserDirectory.PASSWORDS.containsKey("norole-id"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminEditsUserWithoutACisysRole() {
    TestUserDirectory.add("norole-id", "norole", null);
    given()
        .contentType("application/json")
        .body("{\"email\":\"n@example.com\"}")
        .when()
        .put("/api/users/norole-id")
        .then()
        .statusCode(200)
        .body("email", equalTo("n@example.com"), "role", nullValue());
    assertNull(TestUserDirectory.ROLES_BY_ID.get("norole-id"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void leadWithOnlyProjectMemberRoleCannotManageThatProject() {
    // A global lead who is only a project MEMBER must not manage the project's members.
    assign("lead-id", otherProject);
    given()
        .contentType("application/json")
        .body("{\"projectIds\":[" + otherProject + "]}")
        .when()
        .put("/api/users/member2-id")
        .then()
        .statusCode(403);
    assertTrue(assigned("member2-id", otherProject));

    given()
        .contentType("application/json")
        .body(body("intruder2", "member", otherProject))
        .when()
        .post("/api/users")
        .then()
        .statusCode(403);

    given().when().get("/api/users?project=" + otherProject).then().statusCode(404);

    given()
        .when()
        .get("/api/users/projects")
        .then()
        .statusCode(200)
        .body("name", contains("alpha"));

    given()
        .contentType("application/json")
        .body("{\"password\":\"Temp1234\"}")
        .when()
        .put("/api/users/member2-id/password")
        .then()
        .statusCode(404);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminManagesMembershipsInAnyProject() {
    given()
        .contentType("application/json")
        .body("{\"projectIds\":[" + otherProject + "]}")
        .when()
        .put("/api/users/member2-id")
        .then()
        .statusCode(200);
    assertTrue(assigned("member2-id", otherProject));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void multiProjectUpdateRollsBackWhenOneRemovalWouldBreakLastLead() {
    // One removable membership (member of roll-ok), one protected one (sole LEAD of roll-guarded).
    Long ok = project("roll-ok", "other-lead-id");
    assignRole("member2-id", ok, ProjectMember.TeamRole.MEMBER);
    Long guarded = project("roll-guarded", "member2-id");
    given()
        .contentType("application/json")
        .body("{\"projectIds\":[]}")
        .when()
        .put("/api/users/member2-id")
        .then()
        .statusCode(409)
        .body("error", containsString("at least one LEAD"));
    assertEquals(ProjectMember.TeamRole.MEMBER, roleOf("member2-id", ok));
    assertEquals(ProjectMember.TeamRole.LEAD, roleOf("member2-id", guarded));
  }

  @Test
  void staleSessionEntityCannotBypassTheLastLeadCheck() {
    // Fixture: a project whose only member row is MEMBER (created raw, no LEAD rows), used to
    // demonstrate that the stale first-level-cache entity is refreshed under the project lock.
    Long project =
        QuarkusTransaction.requiringNew()
            .call(() -> {
              Project p = new Project();
              p.name = "stale-refresh-" + java.util.UUID.randomUUID();
              p.repositoryUrl = "https://github.com/org/" + p.name;
              p.ownerSubject = "lead-id";
              p.privateRepository = true;
              p.persist();
              ProjectMember m = new ProjectMember();
              m.projectId = p.id;
              m.subject = "member-id";
              m.role = ProjectMember.TeamRole.MEMBER;
              m.createdAt = java.time.Instant.now();
              m.persist();
              return p.id;
            });
    QuarkusTransaction.requiringNew()
        .run(() -> {
          // A stale managed entity in this persistence context, as would be produced by any code
          // path
          // that reads the row before taking the project lock.
          ProjectMember stale =
              ProjectMember.<ProjectMember>find(
                      "projectId = ?1 and subject = ?2", project, "member-id")
                  .firstResult();
          assertEquals(ProjectMember.TeamRole.MEMBER, stale.role);
          // A concurrent promotion commits before the caller acquires the project lock.
          QuarkusTransaction.requiringNew()
              .run(() -> {
                ProjectMember fresh =
                    ProjectMember.<ProjectMember>find(
                            "projectId = ?1 and subject = ?2", project, "member-id")
                        .firstResult();
                fresh.role = ProjectMember.TeamRole.LEAD;
                fresh.persist();
              });
          // The service must see the committed LEAD row and refuse the removal with 409.
          WebApplicationException e =
              assertThrows(
                  WebApplicationException.class,
                  () -> memberService.assign("member-id", null, java.util.Set.of()));
          assertEquals(409, e.getResponse().getStatus());
        });
    // Rolled back: the (now LEAD) row is still present.
    assertEquals(ProjectMember.TeamRole.LEAD, roleOf("member-id", project));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "member-id")})
  void assignedMemberSeesPrivateProject() {
    given().when().get("/api/projects").then().statusCode(200).body("name", contains("alpha"));
    given().when().get("/api/projects/" + leadProject + "/builds").then().statusCode(200);
    given().when().get("/api/projects/" + otherProject + "/builds").then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void profileOnlySavePreservesLeadRoleAndCreatedAt() {
    assignRole("member2-id", leadProject, ProjectMember.TeamRole.LEAD);
    java.time.Instant createdAt = createdAtOf("member2-id", leadProject);
    given()
        .contentType("application/json")
        .body("{\"email\":\"m2@example.com\",\"projectIds\":[" + leadProject + "]}")
        .when()
        .put("/api/users/member2-id")
        .then()
        .statusCode(200);
    assertEquals(ProjectMember.TeamRole.LEAD, roleOf("member2-id", leadProject));
    assertEquals(createdAt, createdAtOf("member2-id", leadProject));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminSaveDoesNotDemoteUnchangedLeadAssignments() {
    Long gamma = project("gamma", "lead-id");
    assignRole("member2-id", leadProject, ProjectMember.TeamRole.LEAD);
    assignRole("member2-id", gamma, ProjectMember.TeamRole.LEAD);
    given()
        .contentType("application/json")
        .body("{\"email\":\"m2@example.com\",\"projectIds\":[" + leadProject + "," + gamma + "]}")
        .when()
        .put("/api/users/member2-id")
        .then()
        .statusCode(200);
    assertEquals(ProjectMember.TeamRole.LEAD, roleOf("member2-id", leadProject));
    assertEquals(ProjectMember.TeamRole.LEAD, roleOf("member2-id", gamma));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void leadCannotRemoveCreatorViaSave() {
    // The creator (member-id) is a global member and the only other LEAD; lead-id is LEAD too.
    Long project = project("omega", "member-id");
    assignRole("lead-id", project, ProjectMember.TeamRole.LEAD);
    given()
        .contentType("application/json")
        .body("{\"projectIds\":[]}")
        .when()
        .put("/api/users/member-id")
        .then()
        .statusCode(403);
    assertEquals(ProjectMember.TeamRole.LEAD, roleOf("member-id", project));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminCanRemoveCreatorWhenAnotherLeadRemains() {
    Long project = project("kappa", "member-id");
    assignRole("other-lead-id", project, ProjectMember.TeamRole.LEAD);
    given()
        .contentType("application/json")
        .body("{\"projectIds\":[]}")
        .when()
        .put("/api/users/member-id")
        .then()
        .statusCode(200);
    assertEquals(null, roleOf("member-id", project));
    assertEquals(ProjectMember.TeamRole.LEAD, roleOf("other-lead-id", project));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminCannotRemoveTheLastLead() {
    // member-id is the sole LEAD of the project.
    Long project = project("solo", "member-id");
    given()
        .contentType("application/json")
        .body("{\"projectIds\":[]}")
        .when()
        .put("/api/users/member-id")
        .then()
        .statusCode(409)
        .body("error", containsString("at least one LEAD"));
    assertEquals(ProjectMember.TeamRole.LEAD, roleOf("member-id", project));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void addIsMemberAndDoesNotTouchExistingLeadMembership() {
    assignRole("member2-id", leadProject, ProjectMember.TeamRole.LEAD);
    String newbieId =
        given()
            .contentType("application/json")
            .body(
                "{\"username\":\"newbie2\",\"password\":\"Secret123\",\"role\":\"member\",\"projectIds\":["
                    + leadProject
                    + "]}")
            .when()
            .post("/api/users")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    // The new membership is a MEMBER; the existing LEAD membership is untouched.
    assertEquals(ProjectMember.TeamRole.MEMBER, roleOf(newbieId, leadProject));
    assertEquals(ProjectMember.TeamRole.LEAD, roleOf("member2-id", leadProject));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminDeleteOfSoleLeadFailsWith409() {
    Long project = project("delete-me-last-lead", "member-id");
    Long team = team("delete-me-last-lead-team", "other-lead-id");
    teamMember(team, "member-id", ProjectMember.TeamRole.MEMBER);
    given().when().delete("/api/users/member-id").then().statusCode(409);
    assertTrue(TestUserDirectory.USERS.containsKey("member-id"));
    assertEquals(ProjectMember.TeamRole.LEAD, roleOf("member-id", project));
    // The rejected deletion is atomic: the team membership is still there.
    assertEquals(1, teamMemberCount(team, "member-id"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminCannotDeleteTheLastTeamLead() {
    Long team = team("delete-last-team-lead", "member-id");

    given()
        .when()
        .delete("/api/users/member-id")
        .then()
        .statusCode(409)
        .body("error", containsString("team must keep at least one LEAD"));

    assertTrue(TestUserDirectory.USERS.containsKey("member-id"));
    assertEquals(ProjectMember.TeamRole.LEAD, teamRole(team, "member-id"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminDeleteSucceedsWhenAnotherLeadRemains() {
    Long project = project("delete-me-another-lead", "member-id");
    assignRole("other-lead-id", project, ProjectMember.TeamRole.LEAD);
    given().when().delete("/api/users/member-id").then().statusCode(204);
    assertFalse(TestUserDirectory.USERS.containsKey("member-id"));
    assertEquals(ProjectMember.TeamRole.LEAD, roleOf("other-lead-id", project));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminDeleteRemovesTeamMemberships() {
    Long team = team("deleted-user-team", "other-lead-id");
    teamMember(team, "member-id", ProjectMember.TeamRole.MEMBER);
    teamMember(team, "member2-id", ProjectMember.TeamRole.LEAD);

    given().when().delete("/api/users/member-id").then().statusCode(204);

    assertEquals(0, teamMemberCount(team, "member-id"));
    assertEquals(1, teamMemberCount(team, "member2-id"), "other members must survive");
    assertNotNull(
        QuarkusTransaction.requiringNew().call(() -> Team.findById(team)),
        "the team itself must survive");
  }
}
