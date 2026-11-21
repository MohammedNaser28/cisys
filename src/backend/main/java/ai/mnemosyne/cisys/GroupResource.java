/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import ai.mnemosyne.cisys.GroupMember.GroupRole;
import ai.mnemosyne.cisys.ProjectMember.TeamRole;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.hibernate.exception.ConstraintViolationException;

/**
 * Groups of users, their membership, and their links to projects. Group membership roles only
 * manage the group; project access comes from a link, whose role is chosen by the project's LEAD.
 * Subjects never leave the server; usernames do.
 */
@Path("/api/groups")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class GroupResource {
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{0,99}");
  static final String NAME_RULE =
      "Group names use letters, digits, spaces, '.', '_' and '-', and start with a letter or digit";

  @Inject ProjectAccess access;
  @Inject SecurityIdentity identity;
  @Inject UserDirectory directory;

  /** One group, with the caller's group role when they are a member. */
  public record GroupView(Long id, String name, String role) {}

  /** A request to create a group. */
  public record CreateGroup(String name) {}

  /** One group member: the user's name and their group role. */
  public record MemberView(String username, String role) {}

  /** A request to add an existing Keycloak user to a group. */
  public record AddMember(String username, String role) {}

  /** A request to change a group member's role. */
  public record ChangeRole(String role) {}

  /** One linked project, with the role every member of the group gets there. */
  public record ProjectView(Long id, String name, String role) {}

  /** A request to link a project to a group. */
  public record LinkProject(Long projectId, String role) {}

  /** All groups; any signed-in cisys user may see the names so project LEADs can link them. */
  @GET
  public List<GroupView> list() {
    access.requireCisysRole();
    String subject = access.subject();
    return UserGroup.<UserGroup>list("order by name").stream()
        .map(g -> new GroupView(g.id, g.name, roleName(GroupMember.roleOf(g.id, subject))))
        .toList();
  }

  /** Creates a group; the creator becomes its LEAD. Global lead or admin only. */
  @POST
  @Transactional
  public Response create(CreateGroup input) {
    access.requireCisysRole();
    if (!(identity.hasRole("lead") || identity.hasRole("admin"))) {
      throw new ForbiddenException();
    }
    String name = name(input == null ? null : input.name());
    if (UserGroup.count("name", name) > 0) {
      throw conflict("A group with this name already exists");
    }
    UserGroup group = new UserGroup();
    group.name = name;
    group.createdAt = Instant.now();
    try {
      group.persistAndFlush();
    } catch (PersistenceException e) {
      if (isDuplicate(e)) {
        throw conflict("A group with this name already exists");
      }
      throw e;
    }
    GroupMember lead = new GroupMember();
    lead.groupId = group.id;
    lead.subject = access.subject();
    lead.role = GroupRole.LEAD;
    lead.createdAt = Instant.now();
    lead.persist();
    return Response.status(201)
        .entity(new GroupView(group.id, group.name, GroupRole.LEAD.name()))
        .build();
  }

  /** Deletes the group and its membership and project links; a group LEAD or global admin only. */
  @DELETE
  @Path("/{id}")
  @Transactional
  public Response delete(@PathParam("id") Long id) {
    UserGroup group = group(id);
    requireManager(group);
    group.delete();
    return Response.noContent().build();
  }

  /** The group's members; visible to its members and global admins. */
  @GET
  @Path("/{id}/members")
  public List<MemberView> members(@PathParam("id") Long id) {
    UserGroup group = group(id);
    String subject = access.subject();
    if (!access.isAdmin() && GroupMember.roleOf(group.id, subject) == null) {
      throw new ForbiddenException();
    }
    return GroupMember.<GroupMember>find("groupId = ?1 order by createdAt, id", id).list().stream()
        .map(m -> new MemberView(usernameOf(m.subject), m.role.name()))
        .toList();
  }

  /**
   * Adds an existing Keycloak user to the group, as MEMBER when no role is given; 400 for a missing
   * username or invalid role, 404 when the user does not exist or the group is not visible, 409
   * when they are already a member. A group LEAD or global admin only.
   */
  @POST
  @Path("/{id}/members")
  @Transactional
  public Response addMember(@PathParam("id") Long id, AddMember input) {
    UserGroup group = group(id);
    requireManager(group);
    if (input == null || input.username() == null || input.username().isBlank()) {
      throw badRequest("username is required");
    }
    String username = input.username().trim();
    GroupRole role = role(input.role(), GroupRole.MEMBER);
    String subject = subjectOf(username);
    if (subject == null) {
      throw notFound("No user named '" + username + "' exists in Keycloak");
    }
    if (GroupMember.roleOf(id, subject) != null) {
      throw conflict("'" + username + "' is already a member of this group");
    }
    GroupMember member = new GroupMember();
    member.groupId = id;
    member.subject = subject;
    member.role = role;
    member.createdAt = Instant.now();
    try {
      member.persistAndFlush();
    } catch (PersistenceException e) {
      if (isDuplicate(e)) {
        throw conflict("'" + username + "' is already a member of this group");
      }
      throw e;
    }
    return Response.status(201).entity(new MemberView(username, role.name())).build();
  }

  /**
   * Sets the member's group role; 400 for a missing or invalid role, 404 when the user is not a
   * member of the group. A group LEAD or global admin only.
   */
  @PUT
  @Path("/{id}/members/{username}")
  @Transactional
  public MemberView changeMember(
      @PathParam("id") Long id, @PathParam("username") String username, ChangeRole input) {
    UserGroup group = managedGroup(id);
    GroupRole role = role(input == null ? null : input.role(), null);
    GroupMember member = existingMember(id, username.trim());
    if (member.role == GroupRole.LEAD && role != GroupRole.LEAD) {
      requireAnotherLead(id);
    }
    member.role = role;
    return new MemberView(username.trim(), role.name());
  }

  /** Removes the member from the group; a group LEAD or global admin only. */
  @DELETE
  @Path("/{id}/members/{username}")
  @Transactional
  public Response removeMember(@PathParam("id") Long id, @PathParam("username") String username) {
    managedGroup(id);
    GroupMember member = existingMember(id, username.trim());
    if (member.role == GroupRole.LEAD) {
      requireAnotherLead(id);
    }
    member.delete();
    return Response.noContent().build();
  }

  /**
   * The group's project links. Group members and global admins see all of them; a project LEAD who
   * is not in the group sees the links for the projects they lead, so they can manage them.
   */
  @GET
  @Path("/{id}/projects")
  public List<ProjectView> projects(@PathParam("id") Long id) {
    UserGroup group = group(id);
    String subject = access.subject();
    boolean groupMember = GroupMember.roleOf(group.id, subject) != null;
    List<ProjectView> views = new ArrayList<>();
    for (GroupProject link : GroupProject.<GroupProject>list("groupId = ?1", id)) {
      Project project = Project.findById(link.projectId);
      if (project == null) {
        continue;
      }
      if (access.isAdmin() || groupMember || access.hasRole(project, TeamRole.LEAD)) {
        views.add(new ProjectView(project.id, project.name, link.role.name()));
      }
    }
    if (!access.isAdmin() && !groupMember && views.isEmpty()) {
      throw new ForbiddenException();
    }
    views.sort(Comparator.comparing(ProjectView::name));
    return views;
  }

  /**
   * Links a project to the group with the role every group member gets there; 400 for a missing
   * project, 404 when the project is not visible, 409 when it is already linked. Requires project
   * LEAD or global admin.
   */
  @POST
  @Path("/{id}/projects")
  @Transactional
  public Response link(@PathParam("id") Long id, LinkProject input) {
    group(id);
    if (input == null || input.projectId() == null) {
      throw badRequest("projectId is required");
    }
    TeamRole role = teamRole(input.role(), TeamRole.MEMBER);
    Project project = access.manageable(input.projectId());
    if (GroupProject.find("groupId = ?1 and projectId = ?2", id, project.id).firstResult()
        != null) {
      throw conflict("This group is already linked to the project");
    }
    GroupProject link = new GroupProject();
    link.groupId = id;
    link.projectId = project.id;
    link.role = role;
    try {
      link.persistAndFlush();
    } catch (PersistenceException e) {
      if (isDuplicate(e)) {
        throw conflict("This group is already linked to the project");
      }
      throw e;
    }
    return Response.status(201)
        .entity(new ProjectView(project.id, project.name, role.name()))
        .build();
  }

  /** Changes the role every group member gets on the project; project LEAD or global admin only. */
  @PUT
  @Path("/{id}/projects/{projectId}")
  @Transactional
  public ProjectView changeLink(
      @PathParam("id") Long id, @PathParam("projectId") Long projectId, ChangeRole input) {
    group(id);
    TeamRole role = teamRole(input == null ? null : input.role(), null);
    Project project = access.manageable(projectId);
    GroupProject link = existingLink(id, projectId);
    link.role = role;
    return new ProjectView(project.id, project.name, role.name());
  }

  /** Removes the group's access to the project; project LEAD or global admin only. */
  @DELETE
  @Path("/{id}/projects/{projectId}")
  @Transactional
  public Response unlink(@PathParam("id") Long id, @PathParam("projectId") Long projectId) {
    group(id);
    access.manageable(projectId);
    existingLink(id, projectId).delete();
    return Response.noContent().build();
  }

  private UserGroup group(Long id) {
    access.requireCisysRole();
    UserGroup group = UserGroup.findById(id);
    if (group == null) {
      throw new NotFoundException();
    }
    return group;
  }

  private void requireManager(UserGroup group) {
    access.requireCisysRole();
    if (access.isAdmin()) {
      return;
    }
    if (GroupMember.roleOf(group.id, access.subject()) != GroupRole.LEAD) {
      throw new ForbiddenException();
    }
  }

  /** Requires group-management permission and locks the group before changing its members. */
  private UserGroup managedGroup(Long id) {
    UserGroup group = group(id);
    requireManager(group);
    Panache.getEntityManager().lock(group, LockModeType.PESSIMISTIC_WRITE);
    return group;
  }

  /** Throws 409 when removing or demoting the final group LEAD. */
  private static void requireAnotherLead(Long groupId) {
    if (GroupMember.count("groupId = ?1 and role = ?2", groupId, GroupRole.LEAD) <= 1) {
      throw conflict("A group must keep at least one LEAD; add another LEAD first");
    }
  }

  private GroupMember existingMember(Long groupId, String username) {
    String subject = subjectOf(username);
    if (subject == null) {
      throw notFound("'" + username + "' is not a member of this group");
    }
    GroupMember member =
        GroupMember.<GroupMember>find("groupId = ?1 and subject = ?2", groupId, subject)
            .firstResult();
    if (member == null) {
      throw notFound("'" + username + "' is not a member of this group");
    }
    return member;
  }

  private static GroupProject existingLink(Long groupId, Long projectId) {
    GroupProject link =
        GroupProject.<GroupProject>find("groupId = ?1 and projectId = ?2", groupId, projectId)
            .firstResult();
    if (link == null) {
      throw notFound("This group is not linked to the project");
    }
    return link;
  }

  private static String roleName(GroupRole role) {
    return role == null ? null : role.name();
  }

  private static String name(String value) {
    if (value == null || value.isBlank()) {
      throw badRequest("name is required");
    }
    String name = value.trim();
    if (!NAME.matcher(name).matches()) {
      throw badRequest(NAME_RULE);
    }
    return name;
  }

  private static GroupRole role(String value, GroupRole fallback) {
    if (value == null || value.isBlank()) {
      if (fallback != null) {
        return fallback;
      }
      throw badRequest("role is required (MEMBER or LEAD)");
    }
    try {
      return GroupRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw badRequest("role must be MEMBER or LEAD");
    }
  }

  private static TeamRole teamRole(String value, TeamRole fallback) {
    if (value == null || value.isBlank()) {
      if (fallback != null) {
        return fallback;
      }
      throw badRequest("role is required (MEMBER or LEAD)");
    }
    try {
      return TeamRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw badRequest("role must be MEMBER or LEAD");
    }
  }

  private String subjectOf(String username) {
    try {
      for (UserDirectory.User u : directory.search(username, 0, 100)) {
        if (username.equalsIgnoreCase(u.username())) {
          return u.id();
        }
      }
      return null;
    } catch (UserDirectory.UnavailableException e) {
      throw badGateway(e);
    }
  }

  private String usernameOf(String subject) {
    return directory.find(subject).map(UserDirectory.User::username).orElse("unknown");
  }

  /**
   * The details name Keycloak's admin endpoints, so they are logged and the client gets a generic
   * message.
   */
  private WebApplicationException badGateway(UserDirectory.UnavailableException e) {
    Log.warnf("User lookup failed: %s", e.getMessage());
    return problem(502, "The user directory is unavailable; try again later");
  }

  private static WebApplicationException notFound(String message) {
    return problem(404, message);
  }

  private static WebApplicationException badRequest(String message) {
    return problem(400, message);
  }

  private static WebApplicationException conflict(String message) {
    return problem(409, message);
  }

  /** Identifies only the unique constraints used by group writes. */
  private static boolean isDuplicate(PersistenceException exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation
          && "23505".equals(violation.getSQLState())
          && ("uk_usergroup_name".equals(violation.getConstraintName())
              || "groupmember_group_subject".equals(violation.getConstraintName())
              || "groupproject_pkey".equals(violation.getConstraintName()))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Entity-carrying JSON error; the framework default for message-only exceptions drops the body.
   */
  private static WebApplicationException problem(int status, String message) {
    return new WebApplicationException(
        Response.status(status)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", message))
            .build());
  }
}
