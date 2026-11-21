/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS
 *   ECLIPSE PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THE AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies effective project roles and the shared team-membership safeguards. */
@QuarkusTest
@TestProfile(TeamTestProfile.class)
class TeamProjectAccessTest {
  @Inject TeamMemberService teamMembers;

  @BeforeEach
  void users() {
    TestUserDirectory.reset();
    TestUserDirectory.add("owner-id", "owner", "lead");
    TestUserDirectory.add("member-id", "member", "member");
    TestUserDirectory.add("outsider-id", "outsider", "member");
    TestUserDirectory.add("admin-id", "admin", "admin");
    TestUserDirectory.add("lead-a-id", "lead-a", "member");
    TestUserDirectory.add("lead-b-id", "lead-b", "member");
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void effectiveRoleUsesTheHigherDirectOrTeamRole() {
    Seed directMember = project("owner-id");
    Long leadTeam = team("team-owner-id", "member-id", ProjectMember.TeamRole.LEAD);
    direct(directMember.id(), "member-id", ProjectMember.TeamRole.MEMBER);
    attach(directMember.id(), leadTeam);

    Seed directLead = project("owner-id");
    Long memberTeam = team("team-owner-id-2", "member-id", ProjectMember.TeamRole.MEMBER);
    direct(directLead.id(), "member-id", ProjectMember.TeamRole.LEAD);
    attach(directLead.id(), memberTeam);

    Seed bothMembers = project("owner-id");
    Long secondMemberTeam = team("team-owner-id-3", "member-id", ProjectMember.TeamRole.MEMBER);
    direct(bothMembers.id(), "member-id", ProjectMember.TeamRole.MEMBER);
    attach(bothMembers.id(), secondMemberTeam);

    assertEquals(
        ProjectMember.TeamRole.LEAD,
        role(directMember.id(), "member-id"),
        "a team LEAD elevates a direct MEMBER");
    assertEquals(
        ProjectMember.TeamRole.LEAD,
        role(directLead.id(), "member-id"),
        "a direct LEAD remains effective over a team MEMBER");
    assertEquals(ProjectMember.TeamRole.MEMBER, role(bothMembers.id(), "member-id"));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void projectIdsIncludeDirectOnlyTeamOnlyAndBoth() {
    Seed directOnly = project("owner-id");
    direct(directOnly.id(), "member-id", ProjectMember.TeamRole.MEMBER);

    Seed teamOnly = project("owner-id");
    Long team = team("team-owner-id", "member-id", ProjectMember.TeamRole.MEMBER);
    attach(teamOnly.id(), team);

    Seed both = project("owner-id");
    Long secondTeam = team("team-owner-id-2", "member-id", ProjectMember.TeamRole.MEMBER);
    direct(both.id(), "member-id", ProjectMember.TeamRole.MEMBER);
    attach(both.id(), secondTeam);

    Seed unrelated = project("owner-id");

    java.util.Set<Long> ids =
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> ProjectRoles.projectIds("member-id", ProjectMember.TeamRole.MEMBER));

    org.junit.jupiter.api.Assertions.assertTrue(ids.contains(directOnly.id()));
    org.junit.jupiter.api.Assertions.assertTrue(ids.contains(teamOnly.id()));
    org.junit.jupiter.api.Assertions.assertTrue(ids.contains(both.id()));
    org.junit.jupiter.api.Assertions.assertFalse(ids.contains(unrelated.id()));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void teamMemberCanSeePrivateProject() {
    Seed project = project("owner-id");
    Long team = team("team-owner-id", "member-id", ProjectMember.TeamRole.MEMBER);
    attach(project.id(), team);

    given().get("/api/project-pages/" + project.name()).then().statusCode(200);
    given()
        .get("/api/projects")
        .then()
        .statusCode(200)
        .body("id", org.hamcrest.Matchers.hasItem(project.id().intValue()));
  }

  @Test
  @TestSecurity(user = "outsider", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "outsider-id"))
  void privateProjectWithoutEffectiveMembershipIs404() {
    Seed project = project("owner-id");
    Long team = team("team-owner-id", "member-id", ProjectMember.TeamRole.MEMBER);
    attach(project.id(), team);

    given().get("/api/project-pages/" + project.name()).then().statusCode(404);
    given()
        .get("/api/projects")
        .then()
        .statusCode(200)
        .body(
            "id",
            org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(project.id().intValue())));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void nonAdminCannotDemoteTeamCreator() {
    Long team = team("member-id", "member-id", ProjectMember.TeamRole.LEAD);

    WebApplicationException error =
        org.junit.jupiter.api.Assertions.assertThrows(
            WebApplicationException.class,
            () ->
                QuarkusTransaction.requiringNew()
                    .run(() ->
                        teamMembers.changeRole(team, "member-id", ProjectMember.TeamRole.MEMBER)));
    assertEquals(403, error.getResponse().getStatus());
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminStillCannotRemoveTheLastTeamLead() {
    Long team = team("owner-id", "owner-id", ProjectMember.TeamRole.LEAD);

    WebApplicationException error =
        org.junit.jupiter.api.Assertions.assertThrows(
            WebApplicationException.class,
            () ->
                QuarkusTransaction.requiringNew().run(() -> teamMembers.remove(team, "owner-id")));
    assertEquals(409, error.getResponse().getStatus());
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void concurrentLeadRemovalKeepsOneLead() throws Exception {
    Long team = team("unrelated-owner", "lead-a-id", ProjectMember.TeamRole.LEAD);
    addTeamMember(team, "lead-b-id", ProjectMember.TeamRole.LEAD);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> first = removeConcurrently(executor, ready, start, team, "lead-a-id");
      Future<Integer> second = removeConcurrently(executor, ready, start, team, "lead-b-id");
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      Set<Integer> statuses =
          Set.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
      assertEquals(Set.of(204, 409), statuses);
      assertEquals(
          1,
          QuarkusTransaction.requiringNew()
              .call(() ->
                  TeamMember.count(
                      "teamId = ?1 and role = ?2", team, ProjectMember.TeamRole.LEAD)));
    } finally {
      executor.shutdownNow();
    }
  }

  private Future<Integer> removeConcurrently(
      ExecutorService executor,
      CountDownLatch ready,
      CountDownLatch start,
      Long team,
      String subject) {
    return executor.submit(() -> {
      ready.countDown();
      start.await();
      try {
        QuarkusTransaction.requiringNew().run(() -> teamMembers.remove(team, subject));
        return 204;
      } catch (WebApplicationException error) {
        return error.getResponse().getStatus();
      }
    });
  }

  private static Seed project(String owner) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          String suffix = UUID.randomUUID().toString();
          Project project = new Project();
          project.name = "team-access-" + suffix;
          project.repositoryUrl = "https://github.com/test/team-access-" + suffix;
          project.ownerSubject = owner;
          project.privateRepository = true;
          project.persist();
          persistDirect(project.id, owner, ProjectMember.TeamRole.LEAD);
          return new Seed(project.id, project.name);
        });
  }

  private static Long team(String owner, String subject, ProjectMember.TeamRole role) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Team team = new Team();
          team.name = "team-access-" + UUID.randomUUID();
          team.ownerSubject = owner;
          team.createdAt = Instant.now();
          team.persist();
          persistTeamMember(team.id, subject, role);
          return team.id;
        });
  }

  private static void addTeamMember(Long teamId, String subject, ProjectMember.TeamRole role) {
    QuarkusTransaction.requiringNew().run(() -> persistTeamMember(teamId, subject, role));
  }

  private static void persistTeamMember(Long teamId, String subject, ProjectMember.TeamRole role) {
    TeamMember member = new TeamMember();
    member.teamId = teamId;
    member.subject = subject;
    member.role = role;
    member.createdAt = Instant.now();
    member.persist();
  }

  private static void direct(Long projectId, String subject, ProjectMember.TeamRole role) {
    QuarkusTransaction.requiringNew().run(() -> persistDirect(projectId, subject, role));
  }

  private static void persistDirect(Long projectId, String subject, ProjectMember.TeamRole role) {
    ProjectMember member = new ProjectMember();
    member.projectId = projectId;
    member.subject = subject;
    member.role = role;
    member.createdAt = Instant.now();
    member.persist();
  }

  private static void attach(Long projectId, Long teamId) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Project project = Project.findById(projectId);
          project.teamId = teamId;
        });
  }

  private static ProjectMember.TeamRole role(Long projectId, String subject) {
    return QuarkusTransaction.requiringNew().call(() -> ProjectRoles.of(projectId, subject));
  }

  private record Seed(Long id, String name) {}
}
