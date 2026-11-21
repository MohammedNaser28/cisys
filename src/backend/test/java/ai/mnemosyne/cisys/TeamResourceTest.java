/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THE AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(TeamTestProfile.class)
class TeamResourceTest {
  @BeforeEach
  void users() {
    TestUserDirectory.reset();
    TestUserDirectory.add("admin-id", "admin", "admin");
    TestUserDirectory.add("lead-id", "lead", "lead");
    TestUserDirectory.add("other-lead-id", "other", "lead");
    TestUserDirectory.add("member-id", "member", "member");
    TestUserDirectory.add("outsider-id", "outsider", "member");
  }

  private static Long team(String owner, String member, ProjectMember.TeamRole role) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Team team = new Team();
          team.name = "team-" + UUID.randomUUID();
          team.ownerSubject = owner;
          team.createdAt = Instant.now();
          team.persist();
          TeamMember ownerRow = new TeamMember();
          ownerRow.teamId = team.id;
          ownerRow.subject = owner;
          ownerRow.role = ProjectMember.TeamRole.LEAD;
          ownerRow.createdAt = Instant.now();
          ownerRow.persist();
          if (member != null) {
            TeamMember memberRow = new TeamMember();
            memberRow.teamId = team.id;
            memberRow.subject = member;
            memberRow.role = role;
            memberRow.createdAt = Instant.now();
            memberRow.persist();
          }
          return team.id;
        });
  }

  private static void member(Long teamId, String subject, ProjectMember.TeamRole role) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          TeamMember row = new TeamMember();
          row.teamId = teamId;
          row.subject = subject;
          row.role = role;
          row.createdAt = Instant.now();
          row.persist();
        });
  }

  private static Long project(String owner) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project project = new Project();
          String suffix = UUID.randomUUID().toString();
          project.name = "team-project-" + suffix;
          project.repositoryUrl = "https://github.com/octocat/team-project-" + suffix;
          project.privateRepository = true;
          project.ownerSubject = owner;
          project.persist();
          ProjectMember lead = new ProjectMember();
          lead.projectId = project.id;
          lead.subject = owner;
          lead.role = ProjectMember.TeamRole.LEAD;
          lead.createdAt = Instant.now();
          lead.persist();
          return project.id;
        });
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void memberCannotCreateATeam() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"No Team\"}")
        .post("/api/teams")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void leadCreatesAndJoinsATeam() {
    String name = "Created-" + UUID.randomUUID();
    int id =
        given()
            .contentType(ContentType.JSON)
            .body("{\"name\":\"" + name + "\"}")
            .post("/api/teams")
            .then()
            .statusCode(201)
            .body("name", equalTo(name), "role", equalTo("LEAD"))
            .body("memberCount", equalTo(1), "tokenSet", equalTo(false))
            .extract()
            .path("id");
    given().get("/api/teams/" + id + "/members").then().statusCode(200).body("$", hasSize(1));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminCreatesAndJoinsAsLead() {
    int id =
        given()
            .contentType(ContentType.JSON)
            .body("{\"name\":\"Admin Team-" + UUID.randomUUID() + "\"}")
            .post("/api/teams")
            .then()
            .statusCode(201)
            .body("role", equalTo("LEAD"))
            .extract()
            .path("id");
    given().get("/api/teams/" + id + "/members").then().statusCode(200).body("$", hasSize(1));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminEmptyTeamBodyIsBadRequest() {
    given().contentType(ContentType.JSON).body("{}").post("/api/teams").then().statusCode(400);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void leadEmptyTeamBodyIsBadRequest() {
    given().contentType(ContentType.JSON).body("{}").post("/api/teams").then().statusCode(400);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void listReportsCountsForEveryVisibleTeam() {
    Long first = team("lead-id", "member-id", ProjectMember.TeamRole.MEMBER);
    Long second = team("lead-id", "other-lead-id", ProjectMember.TeamRole.LEAD);
    Long hidden = team("other-lead-id", "outsider-id", ProjectMember.TeamRole.MEMBER);
    Long projectA = project("lead-id");
    Long projectB = project("lead-id");
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Project a = Project.findById(projectA);
          a.teamId = first;
          Project b = Project.findById(projectB);
          b.teamId = second;
        });

    given()
        .get("/api/teams")
        .then()
        .statusCode(200)
        .body("id", not(hasItem(hidden.intValue())))
        .body("find { it.id == " + first + " }.role", equalTo("LEAD"))
        .body("find { it.id == " + first + " }.memberCount", equalTo(2))
        .body("find { it.id == " + first + " }.projectCount", equalTo(1))
        .body("find { it.id == " + second + " }.role", equalTo("LEAD"))
        .body("find { it.id == " + second + " }.memberCount", equalTo(2))
        .body("find { it.id == " + second + " }.projectCount", equalTo(1));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void teamMemberCanViewButCannotManage() {
    Long id = team("lead-id", "member-id", ProjectMember.TeamRole.MEMBER);
    given()
        .get("/api/teams")
        .then()
        .statusCode(200)
        .body("id", hasItem(id.intValue()))
        .body("find { it.id == " + id + " }.memberCount", equalTo(2))
        .body("find { it.id == " + id + " }.tokenSet", equalTo(false));
    given()
        .contentType(ContentType.JSON)
        .body("{\"username\":\"outsider\"}")
        .post("/api/teams/" + id + "/members")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "outsider", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "outsider-id"))
  void nonMemberGetsNotFound() {
    Long id = team("lead-id", "member-id", ProjectMember.TeamRole.MEMBER);
    given().get("/api/teams/" + id + "/members").then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "outsider", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "outsider-id"))
  void outsiderCannotDeleteATeam() {
    Long id = team("lead-id", "member-id", ProjectMember.TeamRole.MEMBER);
    given().delete("/api/teams/" + id).then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void plainMemberCannotDeleteATeam() {
    Long id = team("lead-id", "member-id", ProjectMember.TeamRole.MEMBER);
    given().delete("/api/teams/" + id).then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void leadAddsRenamesAndRemovesMembers() {
    Long id = team("lead-id", null, null);
    given()
        .contentType(ContentType.JSON)
        .body("{\"username\":\"member\",\"role\":\"MEMBER\"}")
        .post("/api/teams/" + id + "/members")
        .then()
        .statusCode(201)
        .body("role", equalTo("MEMBER"));
    given()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"Renamed Team\"}")
        .put("/api/teams/" + id)
        .then()
        .statusCode(200)
        .body("name", equalTo("Renamed Team"));
    assertEquals(1, teamUpdates(id), "a rename is recorded once");
    given().delete("/api/teams/" + id + "/members/member").then().statusCode(204);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void savingTheSameNameRecordsNoUpdate() {
    Long id = team("lead-id", null, null);
    String current = QuarkusTransaction.requiringNew().call(() -> Team.<Team>findById(id).name);

    given()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"" + current + "\"}")
        .put("/api/teams/" + id)
        .then()
        .statusCode(200)
        .body("name", equalTo(current));

    assertEquals(0, teamUpdates(id), "an unchanged name must not be recorded as a rename");
  }

  private static long teamUpdates(Long id) {
    return QuarkusTransaction.requiringNew()
        .call(() ->
            AuditEvent.count("action = ?1 and targetId = ?2", "team.update", String.valueOf(id)));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void teamMustKeepALeadAndProtectItsCreator() {
    Long id = team("lead-id", "other-lead-id", ProjectMember.TeamRole.LEAD);
    given()
        .contentType(ContentType.JSON)
        .body("{\"role\":\"MEMBER\"}")
        .put("/api/teams/" + id + "/members/lead")
        .then()
        .statusCode(403);
    given().delete("/api/teams/" + id + "/members/other").then().statusCode(204);
    given().delete("/api/teams/" + id + "/members/lead").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminCannotRemoveTheLastLead() {
    Long id = team("lead-id", null, null);
    given().delete("/api/teams/" + id + "/members/lead").then().statusCode(409);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void deletingTeamDetachesProjectsAndKeepsDirectMembers() {
    Long team = team("lead-id", null, null);
    Long project = project("lead-id");
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Project row = Project.findById(project);
          row.teamId = team;
          ProjectMember member = new ProjectMember();
          member.projectId = project;
          member.subject = "member-id";
          member.role = ProjectMember.TeamRole.MEMBER;
          member.createdAt = Instant.now();
          member.persist();
        });
    given().delete("/api/teams/" + team).then().statusCode(204);
    assertNull(
        QuarkusTransaction.requiringNew().call(() -> Project.<Project>findById(project).teamId));
    assertEquals(
        1,
        QuarkusTransaction.requiringNew()
            .call(() ->
                ProjectMember.count("projectId = ?1 and subject = ?2", project, "member-id")));
  }
}
