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
import static org.junit.jupiter.api.Assertions.assertEquals;

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
class ProjectTeamAssignmentResourceTest {
  @BeforeEach
  void users() {
    TestUserDirectory.reset();
    TestUserDirectory.add("admin-id", "admin", "admin");
    TestUserDirectory.add("lead-id", "lead", "lead");
    TestUserDirectory.add("other-lead-id", "other", "lead");
    TestUserDirectory.add("member-id", "member", "member");
    TestUserDirectory.add("outsider-id", "outsider", "member");
  }

  private static Long project(String owner) {
    return project(owner, true);
  }

  private static Long project(String owner, boolean privateRepository) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project project = new Project();
          String suffix = UUID.randomUUID().toString();
          project.name = "assignment-" + suffix;
          project.repositoryUrl = "https://github.com/octocat/assignment-" + suffix;
          project.privateRepository = privateRepository;
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

  private static Long team(String owner, String member, ProjectMember.TeamRole memberRole) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Team team = new Team();
          team.name = "assignment-team-" + UUID.randomUUID();
          team.ownerSubject = owner;
          team.persist();
          TeamMember lead = new TeamMember();
          lead.teamId = team.id;
          lead.subject = owner;
          lead.role = ProjectMember.TeamRole.LEAD;
          lead.persist();
          if (member != null) {
            TeamMember row = new TeamMember();
            row.teamId = team.id;
            row.subject = member;
            row.role = memberRole;
            row.persist();
          }
          return team.id;
        });
  }

  private static void directMember(Long projectId, String subject) {
    directMember(projectId, subject, ProjectMember.TeamRole.LEAD);
  }

  private static void directMember(Long projectId, String subject, ProjectMember.TeamRole role) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          ProjectMember member = new ProjectMember();
          member.projectId = projectId;
          member.subject = subject;
          member.role = role;
          member.createdAt = Instant.now();
          member.persist();
        });
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void creatorCannotAttachATeamWhereTheyAreOnlyAMember() {
    Long project = project("lead-id");
    Long team = team("other-lead-id", "lead-id", ProjectMember.TeamRole.MEMBER);
    given()
        .contentType(ContentType.JSON)
        .body("{\"teamId\":" + team + "}")
        .put("/api/projects/" + project + "/team-assignment")
        .then()
        .statusCode(403);
    given()
        .contentType(ContentType.JSON)
        .body("{}")
        .put("/api/projects/" + project + "/team-assignment")
        .then()
        .statusCode(200)
        .body("teamId", equalTo(null));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void creatorCannotAttachATeamTheyCannotSee() {
    Long project = project("lead-id");
    Long team = team("other-lead-id", null, null);
    given()
        .contentType(ContentType.JSON)
        .body("{\"teamId\":" + team + "}")
        .put("/api/projects/" + project + "/team-assignment")
        .then()
        .statusCode(404);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void directLeadMustLeadTheTargetTeam() {
    Long project = project("other-lead-id");
    directMember(project, "lead-id");
    Long unrelated = team("other-lead-id", null, null);
    given()
        .contentType(ContentType.JSON)
        .body("{\"teamId\":" + unrelated + "}")
        .put("/api/projects/" + project + "/team-assignment")
        .then()
        .statusCode(404);

    Long led = team("other-lead-id", "lead-id", ProjectMember.TeamRole.LEAD);
    given()
        .contentType(ContentType.JSON)
        .body("{\"teamId\":" + led + "}")
        .put("/api/projects/" + project + "/team-assignment")
        .then()
        .statusCode(200);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void projectLeadCannotChangeToATeamTheyCannotSee() {
    Long project = project("other-lead-id");
    directMember(project, "member-id");
    Long team = team("lead-id", null, null);
    given()
        .contentType(ContentType.JSON)
        .body("{\"teamId\":" + team + "}")
        .put("/api/projects/" + project + "/team-assignment")
        .then()
        .statusCode(404);
  }

  @Test
  @TestSecurity(user = "outsider", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "outsider-id"))
  void outsiderGets404ForPrivateProjectTeamChange() {
    Long project = project("other-lead-id");
    Long team = team("lead-id", null, null);
    given()
        .contentType(ContentType.JSON)
        .body("{\"teamId\":" + team + "}")
        .put("/api/projects/" + project + "/team-assignment")
        .then()
        .statusCode(404);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void projectMemberWhoLeadsTheTargetTeamCannotAttachItToAPrivateProject() {
    Long project = project("other-lead-id");
    directMember(project, "member-id", ProjectMember.TeamRole.MEMBER);
    Long team = team("member-id", null, null);
    attachExpecting(project, team, 403);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void projectMemberWhoLeadsTheTargetTeamCannotAttachItToAPublicProject() {
    Long project = project("other-lead-id", false);
    directMember(project, "member-id", ProjectMember.TeamRole.MEMBER);
    Long team = team("member-id", null, null);
    attachExpecting(project, team, 403);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void teamLeadWithoutAProjectRoleCannotAttachToAPublicProject() {
    Long project = project("other-lead-id", false);
    Long team = team("lead-id", null, null);
    attachExpecting(project, team, 403);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void teamLeadWithoutAProjectRoleGets404ForAPrivateProject() {
    // A private project is concealed from callers with no project role, so the answer is 404.
    Long project = project("other-lead-id");
    Long team = team("lead-id", null, null);
    attachExpecting(project, team, 404);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void creatorWhoIsOnlyAMemberOfTheTargetTeamCannotAttachItToAPublicProject() {
    Long project = project("lead-id", false);
    Long team = team("other-lead-id", "lead-id", ProjectMember.TeamRole.MEMBER);
    attachExpecting(project, team, 403);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void directLeadWhoLeadsTheTargetTeamCanAttachItToAPublicProject() {
    Long project = project("other-lead-id", false);
    directMember(project, "lead-id");
    Long team = team("lead-id", null, null);
    attachExpecting(project, team, 200);
  }

  private static void attachExpecting(Long project, Long team, int status) {
    Long before =
        QuarkusTransaction.requiringNew().call(() -> Project.<Project>findById(project).teamId);
    given()
        .contentType(ContentType.JSON)
        .body("{\"teamId\":" + team + "}")
        .put("/api/projects/" + project + "/team-assignment")
        .then()
        .statusCode(status);
    if (status != 200) {
      assertEquals(before, teamOf(project), "a rejected attach must not change the project");
    }
  }

  private static void clearExpecting(Long project, int status) {
    given()
        .contentType(ContentType.JSON)
        .body("{}")
        .put("/api/projects/" + project + "/team-assignment")
        .then()
        .statusCode(status);
  }

  private static void attachTeam(Long projectId, Long teamId) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Project project = Project.findById(projectId);
          project.teamId = teamId;
          project.persistAndFlush();
        });
  }

  private static Long teamOf(Long project) {
    return QuarkusTransaction.requiringNew().call(() -> Project.<Project>findById(project).teamId);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void directMemberCannotClearTheProjectTeam() {
    Long team = team("lead-id", null, null);
    Long project = project("other-lead-id");
    directMember(project, "member-id", ProjectMember.TeamRole.MEMBER);
    attachTeam(project, team);

    clearExpecting(project, 403);

    assertEquals(team, teamOf(project), "a rejected clear must leave the team attached");
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void leadOnlyThroughTheAttachedTeamCannotAttachOrClear() {
    // lead-id is a LEAD of both teams but has no direct row: the project reaches them only through
    // its attached team, and that derived role must not authorize a team change.
    Long attached = team("lead-id", null, null);
    Long other = team("lead-id", null, null);
    Long project = project("other-lead-id");
    attachTeam(project, attached);

    attachExpecting(project, other, 403);
    clearExpecting(project, 403);

    assertEquals(attached, teamOf(project), "neither rejected change may alter the project");
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminCanAttachATeam() {
    Long project = project("other-lead-id");
    Long team = team("lead-id", null, null);
    given()
        .contentType(ContentType.JSON)
        .body("{\"teamId\":" + team + "}")
        .put("/api/projects/" + project + "/team-assignment")
        .then()
        .statusCode(200)
        .body("teamId", equalTo(team.intValue()));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void unknownTargetTeamIs404() {
    Long project = project("lead-id");
    given()
        .contentType(ContentType.JSON)
        .body("{\"teamId\":999999999}")
        .put("/api/projects/" + project + "/team-assignment")
        .then()
        .statusCode(404);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void projectCreationAcceptsAnOptionalTeamLedByTheCreator() {
    Long team = team("lead-id", null, null);
    String suffix = UUID.randomUUID().toString();
    given()
        .contentType(ContentType.JSON)
        .body(
            "{\"name\":\"created-team-"
                + suffix
                + "\",\"repositoryUrl\":\"https://github.com/octocat/created-team-"
                + suffix
                + "\",\"privateRepository\":true,\"teamId\":"
                + team
                + "}")
        .post("/api/projects")
        .then()
        .statusCode(201)
        .body("teamId", equalTo(team.intValue()));
    assertEquals(
        team,
        QuarkusTransaction.requiringNew()
            .call(() ->
                Project.<Project>find("name", "created-team-" + suffix).firstResult().teamId));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void projectCreationRejectsATeamWhereCreatorIsOnlyAMember() {
    Long team = team("other-lead-id", "lead-id", ProjectMember.TeamRole.MEMBER);
    String suffix = UUID.randomUUID().toString();
    given()
        .contentType(ContentType.JSON)
        .body(
            "{\"name\":\"member-team-"
                + suffix
                + "\",\"repositoryUrl\":\"https://github.com/octocat/member-team-"
                + suffix
                + "\",\"privateRepository\":true,\"teamId\":"
                + team
                + "}")
        .post("/api/projects")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void projectCreationHidesATeamTheCreatorCannotSee() {
    Long team = team("other-lead-id", null, null);
    String suffix = UUID.randomUUID().toString();
    given()
        .contentType(ContentType.JSON)
        .body(
            "{\"name\":\"hidden-team-"
                + suffix
                + "\",\"repositoryUrl\":\"https://github.com/octocat/hidden-team-"
                + suffix
                + "\",\"privateRepository\":true,\"teamId\":"
                + team
                + "}")
        .post("/api/projects")
        .then()
        .statusCode(404);
  }
}
