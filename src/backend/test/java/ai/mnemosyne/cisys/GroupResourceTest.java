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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.mnemosyne.cisys.GroupMember.GroupRole;
import ai.mnemosyne.cisys.ProjectMember.TeamRole;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.restassured.http.ContentType;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(TeamTestProfile.class)
class GroupResourceTest {

  @BeforeEach
  void users() {
    TestUserDirectory.reset();
    TestUserDirectory.add("lead-id", "lead", "lead");
    TestUserDirectory.add("member-id", "member", "member");
    TestUserDirectory.add("other-id", "other", "lead");
    TestUserDirectory.add("admin-id", "admin", "admin");
    TestUserDirectory.add("outsider-id", "outsider", "member");
    TestUserDirectory.add("group-lead-id", "grouplead", "member");
    TestUserDirectory.add("newbie-id", "newbie", "member");
  }

  /** Creates a fresh project with {@code owner} as its direct LEAD. */
  private static Long project(String owner, boolean priv) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project p = new Project();
          String n = UUID.randomUUID().toString();
          p.name = "group-" + n;
          p.repositoryUrl = "https://github.com/octocat/group-" + n;
          p.ownerSubject = owner;
          p.privateRepository = priv;
          p.persist();
          persistDirectMembership(p.id, owner, TeamRole.LEAD);
          return p.id;
        });
  }

  private static void directMembership(Long projectId, String subject, TeamRole role) {
    QuarkusTransaction.requiringNew().run(() -> persistDirectMembership(projectId, subject, role));
  }

  private static void persistDirectMembership(Long projectId, String subject, TeamRole role) {
    ProjectMember m = new ProjectMember();
    m.projectId = projectId;
    m.subject = subject;
    m.role = role;
    m.createdAt = Instant.now();
    m.persist();
  }

  /** Creates a group with {@code creator} as its group LEAD. */
  private static Long group(String name, String creator) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          UserGroup g = new UserGroup();
          g.name = name;
          g.createdAt = Instant.now();
          g.persist();
          persistGroupMember(g.id, creator, GroupRole.LEAD);
          return g.id;
        });
  }

  private static void groupMember(Long groupId, String subject, GroupRole role) {
    QuarkusTransaction.requiringNew().run(() -> persistGroupMember(groupId, subject, role));
  }

  private static void persistGroupMember(Long groupId, String subject, GroupRole role) {
    GroupMember m = new GroupMember();
    m.groupId = groupId;
    m.subject = subject;
    m.role = role;
    m.createdAt = Instant.now();
    m.persist();
  }

  private static void link(Long groupId, Long projectId, TeamRole role) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          GroupProject link = new GroupProject();
          link.groupId = groupId;
          link.projectId = projectId;
          link.role = role;
          link.persist();
        });
  }

  private static String projectName(Long id) {
    return QuarkusTransaction.requiringNew().call(() -> Project.<Project>findById(id).name);
  }

  private static String projectBody(Long id) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project p = Project.findById(id);
          return "{\"name\":\""
              + p.name
              + "\",\"repositoryUrl\":\""
              + p.repositoryUrl
              + "\",\"privateRepository\":"
              + p.privateRepository
              + "}";
        });
  }

  private static long groupMemberCount(Long groupId) {
    return QuarkusTransaction.requiringNew().call(() -> GroupMember.count("groupId = ?1", groupId));
  }

  private static long groupProjectCount(Long groupId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> GroupProject.count("groupId = ?1", groupId));
  }

  private static long directLeadCount(Long projectId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> ProjectMember.count("projectId = ?1 and role = ?2", projectId, TeamRole.LEAD));
  }

  private static String uniqueGroupName() {
    return "Group-" + UUID.randomUUID();
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void groupMemberSeesLinkedPrivateProject() {
    Long project = project("lead-id", true);
    Long group = group(uniqueGroupName(), "lead-id");
    groupMember(group, "member-id", GroupRole.MEMBER);
    link(group, project, TeamRole.MEMBER);

    given().get("/api/project-pages/" + projectName(project)).then().statusCode(200);
    given()
        .get("/api/projects")
        .then()
        .statusCode(200)
        .body(
            "id",
            hasItem(project.intValue()),
            "find { it.id == " + project + " }.effectiveRole",
            equalTo("MEMBER"));
  }

  @Test
  @TestSecurity(user = "outsider", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "outsider-id"))
  void nonGroupMemberGetsNotFound() {
    Long project = project("lead-id", true);
    Long group = group(uniqueGroupName(), "lead-id");
    groupMember(group, "member-id", GroupRole.MEMBER);
    link(group, project, TeamRole.MEMBER);

    given().get("/api/project-pages/" + projectName(project)).then().statusCode(404);
    given()
        .get("/api/projects")
        .then()
        .statusCode(200)
        .body("id", not(hasItem(project.intValue())));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void memberLinkCannotEditProject() {
    Long project = project("lead-id", true);
    Long group = group(uniqueGroupName(), "lead-id");
    groupMember(group, "member-id", GroupRole.MEMBER);
    link(group, project, TeamRole.MEMBER);

    given()
        .contentType(ContentType.JSON)
        .body(projectBody(project))
        .put("/api/projects/" + project)
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void leadLinkCanEditProject() {
    Long project = project("lead-id", true);
    Long group = group(uniqueGroupName(), "lead-id");
    groupMember(group, "member-id", GroupRole.MEMBER);
    link(group, project, TeamRole.LEAD);

    given()
        .contentType(ContentType.JSON)
        .body(projectBody(project))
        .put("/api/projects/" + project)
        .then()
        .statusCode(200);
  }

  @Test
  @TestSecurity(user = "grouplead", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "group-lead-id"))
  void groupLeadWithMemberLinkCannotEditProject() {
    Long project = project("other-id", true);
    Long group = group(uniqueGroupName(), "group-lead-id");
    link(group, project, TeamRole.MEMBER);

    given()
        .contentType(ContentType.JSON)
        .body(projectBody(project))
        .put("/api/projects/" + project)
        .then()
        .statusCode(403);
    // The group role does not let its LEAD change the project's link role either.
    given()
        .contentType(ContentType.JSON)
        .body("{\"projectId\":" + project + ",\"role\":\"LEAD\"}")
        .post("/api/groups/" + group + "/projects")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "newbie", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "newbie-id"))
  void groupMembershipAloneGrantsOnlyTheLinkRole() {
    Long project = project("lead-id", true);
    Long group = group(uniqueGroupName(), "lead-id");
    groupMember(group, "newbie-id", GroupRole.MEMBER);
    link(group, project, TeamRole.MEMBER);

    given().get("/api/project-pages/" + projectName(project)).then().statusCode(200);
    given()
        .contentType(ContentType.JSON)
        .body(projectBody(project))
        .put("/api/projects/" + project)
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void highestRoleWinsAcrossDirectAndGroupMembership() {
    Long directLead = project("lead-id", true);
    Long groupA = group(uniqueGroupName(), "lead-id");
    groupMember(groupA, "member-id", GroupRole.MEMBER);
    link(groupA, directLead, TeamRole.MEMBER);
    directMembership(directLead, "member-id", TeamRole.LEAD);
    given()
        .contentType(ContentType.JSON)
        .body(projectBody(directLead))
        .put("/api/projects/" + directLead)
        .then()
        .statusCode(200);

    Long groupLead = project("lead-id", true);
    Long groupB = group(uniqueGroupName(), "lead-id");
    groupMember(groupB, "member-id", GroupRole.MEMBER);
    link(groupB, groupLead, TeamRole.LEAD);
    directMembership(groupLead, "member-id", TeamRole.MEMBER);
    given()
        .contentType(ContentType.JSON)
        .body(projectBody(groupLead))
        .put("/api/projects/" + groupLead)
        .then()
        .statusCode(200);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void unlinkingRemovesAccess() {
    Long project = project("lead-id", true);
    Long group = group(uniqueGroupName(), "lead-id");
    groupMember(group, "member-id", GroupRole.MEMBER);
    link(group, project, TeamRole.MEMBER);

    given().get("/api/project-pages/" + projectName(project)).then().statusCode(200);
    QuarkusTransaction.requiringNew()
        .run(() -> GroupProject.delete("groupId = ?1 and projectId = ?2", group, project));
    given().get("/api/project-pages/" + projectName(project)).then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void projectLeadUnlinksGroup() {
    Long project = project("lead-id", true);
    Long group = group(uniqueGroupName(), "lead-id");
    link(group, project, TeamRole.MEMBER);

    given().delete("/api/groups/" + group + "/projects/" + project).then().statusCode(204);
    assertEquals(0, groupProjectCount(group));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void deletingGroupCascadesMembersAndLinks() {
    Long project = project("lead-id", true);
    Long group = group(uniqueGroupName(), "lead-id");
    groupMember(group, "member-id", GroupRole.MEMBER);
    link(group, project, TeamRole.MEMBER);

    given().delete("/api/groups/" + group).then().statusCode(204);
    assertEquals(0, groupMemberCount(group));
    assertEquals(0, groupProjectCount(group));
    assertNull(QuarkusTransaction.requiringNew().call(() -> UserGroup.findById(group)));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void deletingProjectCascadesLinks() {
    Long project = project("lead-id", true);
    Long group = group(uniqueGroupName(), "lead-id");
    groupMember(group, "member-id", GroupRole.MEMBER);
    link(group, project, TeamRole.MEMBER);

    given().delete("/api/projects/" + project).then().statusCode(204);
    assertEquals(0, groupProjectCount(group));
    assertEquals(
        1,
        QuarkusTransaction.requiringNew()
            .call(() -> GroupMember.count("groupId = ?1 and subject = ?2", group, "member-id")),
        "project deletion must not remove group members");
    assertNotNull(QuarkusTransaction.requiringNew().call(() -> UserGroup.findById(group)));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void directLeadInvariantStillCountsDirectLeadsOnly() {
    Long project = project("lead-id", true);
    Long group = group(uniqueGroupName(), "lead-id");
    groupMember(group, "member-id", GroupRole.MEMBER);
    link(group, project, TeamRole.LEAD);

    assertEquals(
        TeamRole.LEAD,
        QuarkusTransaction.requiringNew().call(() -> ProjectRoles.of(project, "member-id")));
    given()
        .contentType(ContentType.JSON)
        .body("{\"role\":\"MEMBER\"}")
        .put("/api/projects/" + project + "/team/lead")
        .then()
        .statusCode(409);
    assertEquals(1, directLeadCount(project));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void memberCannotCreateGroup() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"name\":\"" + uniqueGroupName() + "\"}")
        .post("/api/groups")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void leadCreatesGroupAndBecomesItsLead() {
    String name = uniqueGroupName();
    int id =
        given()
            .contentType(ContentType.JSON)
            .body("{\"name\":\"" + name + "\"}")
            .post("/api/groups")
            .then()
            .statusCode(201)
            .body("name", equalTo(name), "role", equalTo("LEAD"))
            .extract()
            .path("id");
    assertEquals(
        GroupRole.LEAD,
        QuarkusTransaction.requiringNew().call(() -> GroupMember.roleOf((long) id, "lead-id")));
  }

  @Test
  @TestSecurity(user = "grouplead", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "group-lead-id"))
  void groupLeadManagesMembers() {
    Long group = group(uniqueGroupName(), "group-lead-id");
    given()
        .contentType(ContentType.JSON)
        .body("{\"username\":\"newbie\",\"role\":\"LEAD\"}")
        .post("/api/groups/" + group + "/members")
        .then()
        .statusCode(201)
        .body("username", equalTo("newbie"), "role", equalTo("LEAD"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void cannotDemoteTheLastGroupLead() {
    Long group = group(uniqueGroupName(), "lead-id");

    given()
        .contentType(ContentType.JSON)
        .body("{\"role\":\"MEMBER\"}")
        .put("/api/groups/" + group + "/members/lead")
        .then()
        .statusCode(409)
        .body("error", equalTo("A group must keep at least one LEAD; add another LEAD first"));
    assertEquals(
        GroupRole.LEAD,
        QuarkusTransaction.requiringNew().call(() -> GroupMember.roleOf(group, "lead-id")));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void cannotRemoveTheLastGroupLead() {
    Long group = group(uniqueGroupName(), "lead-id");

    given()
        .delete("/api/groups/" + group + "/members/lead")
        .then()
        .statusCode(409)
        .body("error", equalTo("A group must keep at least one LEAD; add another LEAD first"));
    assertEquals(1, groupMemberCount(group));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void canDemoteAndRemoveAGroupLeadWhenAnotherLeadExists() {
    Long group = group(uniqueGroupName(), "lead-id");
    groupMember(group, "other-id", GroupRole.LEAD);

    given()
        .contentType(ContentType.JSON)
        .body("{\"role\":\"MEMBER\"}")
        .put("/api/groups/" + group + "/members/other")
        .then()
        .statusCode(200)
        .body("role", equalTo("MEMBER"));
    given().delete("/api/groups/" + group + "/members/other").then().statusCode(204);
    assertEquals(1, groupMemberCount(group));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminCannotDemoteOrRemoveTheLastGroupLead() {
    Long group = group(uniqueGroupName(), "lead-id");

    given()
        .contentType(ContentType.JSON)
        .body("{\"role\":\"MEMBER\"}")
        .put("/api/groups/" + group + "/members/lead")
        .then()
        .statusCode(409);
    given().delete("/api/groups/" + group + "/members/lead").then().statusCode(409);
    assertEquals(1, groupMemberCount(group));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void concurrentRemovalOfTwoGroupLeadsLeavesOne() throws Exception {
    Long group = group(uniqueGroupName(), "lead-id");
    groupMember(group, "other-id", GroupRole.LEAD);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<Integer> first =
          pool.submit(() -> {
            start.await();
            return given()
                .delete("/api/groups/" + group + "/members/lead")
                .then()
                .extract()
                .statusCode();
          });
      Future<Integer> second =
          pool.submit(() -> {
            start.await();
            return given()
                .delete("/api/groups/" + group + "/members/other")
                .then()
                .extract()
                .statusCode();
          });
      start.countDown();
      int firstStatus = first.get(10, TimeUnit.SECONDS);
      int secondStatus = second.get(10, TimeUnit.SECONDS);
      assertTrue(
          (firstStatus == 204 && secondStatus == 409)
              || (firstStatus == 409 && secondStatus == 204));
    }
    assertEquals(
        1,
        QuarkusTransaction.requiringNew()
            .call(() -> GroupMember.count("groupId = ?1 and role = ?2", group, GroupRole.LEAD)));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void groupMemberCannotManageMembers() {
    Long group = group(uniqueGroupName(), "lead-id");
    groupMember(group, "member-id", GroupRole.MEMBER);
    given()
        .contentType(ContentType.JSON)
        .body("{\"username\":\"newbie\",\"role\":\"MEMBER\"}")
        .post("/api/groups/" + group + "/members")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "grouplead", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "group-lead-id"))
  void nonProjectLeadCannotLinkPrivateProject() {
    Long group = group(uniqueGroupName(), "group-lead-id");
    Long project = project("lead-id", true);
    given()
        .contentType(ContentType.JSON)
        .body("{\"projectId\":" + project + "}")
        .post("/api/groups/" + group + "/projects")
        .then()
        .statusCode(404);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void projectLeadSetsLinkRole() {
    Long project = project("lead-id", true);
    Long group = group(uniqueGroupName(), "lead-id");
    link(group, project, TeamRole.MEMBER);

    given()
        .contentType(ContentType.JSON)
        .body("{\"role\":\"LEAD\"}")
        .put("/api/groups/" + group + "/projects/" + project)
        .then()
        .statusCode(200)
        .body("id", equalTo(project.intValue()), "role", equalTo("LEAD"));
    assertEquals(
        TeamRole.LEAD,
        QuarkusTransaction.requiringNew()
            .call(() ->
                GroupProject.<GroupProject>find("groupId = ?1 and projectId = ?2", group, project)
                    .firstResult()
                    .role));
  }
}
