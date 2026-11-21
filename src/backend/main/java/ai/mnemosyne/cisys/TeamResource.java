/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THE AGREEMENT.
 */

package ai.mnemosyne.cisys;

import ai.mnemosyne.cisys.ProjectMember.TeamRole;
import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.hibernate.exception.ConstraintViolationException;

/** Teams, their members, and team-level management permissions. */
@Path("/api/teams")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class TeamResource {
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{0,99}");
  private static final String NAME_RULE =
      "Team names use letters, digits, spaces, '.', '_' and '-', and start with a letter or digit";

  /** Repository tokens are short credentials; the cap keeps a request body from being unbounded. */
  static final int MAX_TOKEN_LENGTH = 4096;

  @Inject ProjectAccess access;
  @Inject EntityManager em;
  @Inject AuditService audit;
  @Inject SecurityIdentity identity;
  @Inject UserDirectory directory;
  @Inject TeamMemberService membersService;
  @Inject CredentialCipher cipher;
  @Inject GitRepositoryService repositories;

  /** A team visible to the caller; the token itself is never exposed. */
  public record TeamView(
      Long id, String name, String role, long projectCount, long memberCount, boolean tokenSet) {}

  /** A request to create a team. The creator is always added as a LEAD. */
  public record CreateTeam(String name) {}

  /** A request to rename a team. */
  public record UpdateTeam(String name) {}

  /** One team member, represented by username rather than subject. */
  public record MemberView(String username, String role) {}

  /** A request to add a user to a team. */
  public record AddMember(String username, String role) {}

  /** A request to change a team member's role. */
  public record ChangeRole(String role) {}

  /** A write-only team token and an optional private project used to validate it. */
  public record TokenRequest(String token, Long projectId) {}

  /** Token state; the encrypted token and plaintext token are never returned. */
  public record TokenView(boolean set, Instant updatedAt, String updatedBy) {}

  /** Lists all teams for admins and the teams of which the caller is a member. */
  @GET
  public List<TeamView> list() {
    access.requireCisysRole();
    boolean admin = access.isAdmin();
    List<Team> teams =
        admin
            ? Team.list("order by name")
            : Team.list(
                "id in (select tm.teamId from TeamMember tm where tm.subject = ?1) order by name",
                access.subject());
    if (teams.isEmpty()) {
      return List.of();
    }
    // One query per aggregate instead of per team: a team list must not become an N+1.
    List<Long> ids = teams.stream().map(team -> team.id).toList();
    Map<Long, Long> projectCounts =
        counts(
            "select p.teamId, count(p) from Project p where p.teamId in ?1 group by p.teamId", ids);
    Map<Long, Long> memberCounts =
        counts(
            "select m.teamId, count(m) from TeamMember m where m.teamId in ?1 group by m.teamId",
            ids);
    Map<Long, TeamRole> roles = new HashMap<>();
    if (!admin) {
      for (Object[] row :
          em.createQuery(
                  "select m.teamId, m.role from TeamMember m"
                      + " where m.teamId in ?1 and m.subject = ?2",
                  Object[].class)
              .setParameter(1, ids)
              .setParameter(2, access.subject())
              .getResultList()) {
        roles.put((Long) row[0], (TeamRole) row[1]);
      }
    }
    return teams.stream()
        .map(team ->
            new TeamView(
                team.id,
                team.name,
                admin ? "ADMIN" : roleName(roles.get(team.id)),
                projectCounts.getOrDefault(team.id, 0L),
                memberCounts.getOrDefault(team.id, 0L),
                team.tokenSecret != null))
        .toList();
  }

  private Map<Long, Long> counts(String query, List<Long> teamIds) {
    Map<Long, Long> result = new HashMap<>();
    for (Object[] row :
        em.createQuery(query, Object[].class).setParameter(1, teamIds).getResultList()) {
      result.put((Long) row[0], (Long) row[1]);
    }
    return result;
  }

  /** Creates a team and adds the creator as its first LEAD. */
  @POST
  @Transactional
  @Audited(action = AuditAction.TEAM_CREATE, target = AuditTargetType.TEAM, summary = "Create team")
  public Response create(CreateTeam input) {
    access.requireCisysRole();
    if (!identity.hasRole("lead") && !identity.hasRole("admin")) {
      throw new ForbiddenException();
    }
    String name = name(input == null ? null : input.name());
    if (Team.count("lower(name) = ?1", name.toLowerCase(Locale.ROOT)) > 0) {
      throw conflict("A team with this name already exists");
    }
    Team team = new Team();
    team.name = name;
    team.ownerSubject = access.subject();
    team.createdAt = Instant.now();
    try {
      team.persistAndFlush();
    } catch (PersistenceException e) {
      if (isDuplicate(e)) {
        throw conflict("A team with this name already exists");
      }
      throw e;
    }
    membersService.add(team.id, access.subject(), TeamRole.LEAD);
    audit.success(
        AuditAction.TEAM_CREATE,
        AuditTargetType.TEAM,
        team.id,
        team.name,
        (Project) null,
        "Created team " + team.name,
        AuditDetails.of("name", team.name));
    audit.success(
        AuditAction.TEAM_MEMBER_ADD,
        AuditTargetType.TEAM_MEMBER,
        access.subject(),
        usernameOf(access.subject()),
        (Project) null,
        "Added the creator as team LEAD",
        AuditDetails.of("role", TeamRole.LEAD.name(), "initial", true, "teamId", team.id));
    return Response.status(201)
        .entity(new TeamView(team.id, team.name, TeamRole.LEAD.name(), 0, 1, false))
        .build();
  }

  /** Renames a team; a team LEAD or global admin only. */
  @PUT
  @Path("/{id}")
  @Transactional
  @Audited(action = AuditAction.TEAM_UPDATE, target = AuditTargetType.TEAM, summary = "Update team")
  public TeamView update(@PathParam("id") Long id, UpdateTeam input) {
    Team team = managed(id);
    String previousName = team.name;
    String name = name(input == null ? null : input.name());
    if (!name.equals(team.name)
        && Team.count("lower(name) = ?1 and id <> ?2", name.toLowerCase(Locale.ROOT), id) > 0) {
      throw conflict("A team with this name already exists");
    }
    team.name = name;
    try {
      team.persistAndFlush();
    } catch (PersistenceException e) {
      if (isDuplicate(e)) {
        throw conflict("A team with this name already exists");
      }
      throw e;
    }
    if (!name.equals(previousName)) {
      audit.success(
          AuditAction.TEAM_UPDATE,
          AuditTargetType.TEAM,
          team.id,
          team.name,
          (Project) null,
          "Updated team " + team.name,
          AuditDetails.of("nameBefore", previousName, "nameAfter", team.name));
    }
    return view(team);
  }

  /** Deletes a team and detaches its projects; direct project members remain unchanged. */
  @DELETE
  @Path("/{id}")
  @Transactional
  @Audited(action = AuditAction.TEAM_DELETE, target = AuditTargetType.TEAM, summary = "Delete team")
  public Response delete(@PathParam("id") Long id) {
    Team team = managed(id);
    String name = team.name;
    // Compound mutations lock team rows first, then project rows; see TeamMemberService.
    List<Project> detached =
        Project.<Project>find("teamId", team.id).withLock(LockModeType.PESSIMISTIC_WRITE).list();
    for (Project project : detached) {
      project.teamId = null;
      // One event per project, so each detachment is visible in the project's own history.
      audit.success(
          AuditAction.PROJECT_TEAM_UPDATE,
          AuditTargetType.PROJECT,
          project.id,
          project.name,
          project,
          "Cleared the team from project " + project.name,
          AuditDetails.of("teamBefore", id, "teamAfter", null));
    }
    membersService.removeAll(team.id);
    team.delete();
    audit.success(
        AuditAction.TEAM_DELETE,
        AuditTargetType.TEAM,
        id,
        name,
        (Long) null,
        (String) null,
        "Deleted team " + name,
        AuditDetails.of("name", name));
    return Response.noContent().build();
  }

  /** Lists members for team members and global admins. */
  @GET
  @Path("/{id}/members")
  public List<MemberView> members(@PathParam("id") Long id) {
    visible(id);
    return TeamMember.<TeamMember>find("teamId = ?1 order by createdAt, id", id).list().stream()
        .map(member -> new MemberView(usernameOf(member.subject), member.role.name()))
        .toList();
  }

  /** Adds a user as MEMBER by default; a team LEAD or global admin only. */
  @POST
  @Path("/{id}/members")
  @Transactional
  @Audited(
      action = AuditAction.TEAM_MEMBER_ADD,
      target = AuditTargetType.TEAM_MEMBER,
      summary = "Add team member")
  public Response addMember(@PathParam("id") Long id, AddMember input) {
    managed(id);
    if (input == null || input.username() == null || input.username().isBlank()) {
      throw badRequest("username is required");
    }
    String username = input.username().trim();
    TeamRole role = role(input.role(), TeamRole.MEMBER);
    String subject = subjectOf(username);
    if (subject == null) {
      throw notFound("No user named '" + username + "' exists in Keycloak");
    }
    if (TeamMember.roleOf(id, subject) != null) {
      throw conflict("'" + username + "' is already a member of this team");
    }
    TeamMember member = membersService.add(id, subject, role);
    Team team = Team.findById(id);
    audit.success(
        AuditAction.TEAM_MEMBER_ADD,
        AuditTargetType.TEAM_MEMBER,
        subject,
        username,
        (Project) null,
        "Added " + username + " to team " + team.name,
        AuditDetails.of("teamId", id, "role", role.name()));
    return Response.status(201).entity(new MemberView(username, member.role.name())).build();
  }

  /** Changes a member's role; a team LEAD or global admin only. */
  @PUT
  @Path("/{id}/members/{username}")
  @Transactional
  @Audited(
      action = AuditAction.TEAM_MEMBER_ROLE,
      target = AuditTargetType.TEAM_MEMBER,
      summary = "Change team member role")
  public MemberView changeMember(
      @PathParam("id") Long id, @PathParam("username") String username, ChangeRole input) {
    managed(id);
    String normalized = normalizedUsername(username);
    TeamRole role = role(input == null ? null : input.role(), null);
    String subject = existingSubject(id, normalized);
    TeamRole previous = TeamMember.roleOf(id, subject);
    membersService.changeRole(id, subject, role);
    if (previous != role) {
      audit.success(
          AuditAction.TEAM_MEMBER_ROLE,
          AuditTargetType.TEAM_MEMBER,
          subject,
          normalized,
          (Project) null,
          "Changed " + normalized + "'s role in team",
          AuditDetails.of("teamId", id, "previous", previous.name(), "current", role.name()));
    }
    return new MemberView(normalized, role.name());
  }

  /** Removes a member; a team LEAD or global admin only. */
  @DELETE
  @Path("/{id}/members/{username}")
  @Transactional
  @Audited(
      action = AuditAction.TEAM_MEMBER_REMOVE,
      target = AuditTargetType.TEAM_MEMBER,
      summary = "Remove team member")
  public Response removeMember(@PathParam("id") Long id, @PathParam("username") String username) {
    managed(id);
    String normalized = normalizedUsername(username);
    String subject = existingSubject(id, normalized);
    TeamRole removedRole = TeamMember.roleOf(id, subject);
    membersService.remove(id, subject);
    audit.success(
        AuditAction.TEAM_MEMBER_REMOVE,
        AuditTargetType.TEAM_MEMBER,
        subject,
        normalized,
        (Project) null,
        "Removed " + normalized + " from team",
        AuditDetails.of("teamId", id, "role", removedRole.name()));
    return Response.noContent().build();
  }

  /** Sets or rotates a team token, optionally validating it against one of the team's projects. */
  @PUT
  @Path("/{id}/token")
  @Transactional
  @Audited(
      action = AuditAction.TEAM_TOKEN_UPDATE,
      target = AuditTargetType.TEAM,
      summary = "Set team token")
  public TokenView setToken(@PathParam("id") Long id, TokenRequest input) {
    // Authorization comes first so an unauthorized caller cannot probe whether encryption is
    // configured.
    Team team = managed(id);
    if (input == null || input.token() == null || input.token().isBlank()) {
      throw badRequest("token is required");
    }
    if (input.token().length() > MAX_TOKEN_LENGTH) {
      throw badRequest("The token must be at most " + MAX_TOKEN_LENGTH + " characters");
    }
    checkKey();
    Project validationProject = validationProject(team, input.projectId());
    if (validationProject != null) {
      try {
        repositories.verifyToken(validationProject.repositoryUrl, input.token());
      } catch (IllegalArgumentException e) {
        throw badRequest(e.getMessage());
      } catch (GitRepositoryService.BranchLookupException e) {
        throw badRequest("The token could not access the repository");
      }
    }
    byte[] secret;
    try {
      secret = cipher.encrypt(input.token(), team.id);
    } catch (CredentialCipher.CredentialException e) {
      throw serviceUnavailable(e.getMessage());
    }
    team.tokenSecret = secret;
    team.tokenUpdatedAt = Instant.now();
    team.tokenUpdatedBy = access.subject();
    audit.success(
        AuditAction.TEAM_TOKEN_UPDATE,
        AuditTargetType.TEAM,
        team.id,
        team.name,
        (Project) null,
        "Set team token",
        AuditDetails.of("set", true));
    return tokenView(team);
  }

  /** Clears a team token and records the actor and time of the clear operation. */
  @DELETE
  @Path("/{id}/token")
  @Transactional
  @Audited(
      action = AuditAction.TEAM_TOKEN_UPDATE,
      target = AuditTargetType.TEAM,
      summary = "Clear team token")
  public TokenView clearToken(@PathParam("id") Long id) {
    Team team = managed(id);
    checkKey();
    team.tokenSecret = null;
    team.tokenUpdatedAt = Instant.now();
    team.tokenUpdatedBy = access.subject();
    audit.success(
        AuditAction.TEAM_TOKEN_UPDATE,
        AuditTargetType.TEAM,
        team.id,
        team.name,
        (Project) null,
        "Cleared team token",
        AuditDetails.of("set", false));
    return tokenView(team);
  }

  private Project validationProject(Team team, Long projectId) {
    if (projectId == null) {
      return null;
    }
    Project project = Project.findById(projectId);
    if (project == null || !project.id.equals(projectId) || !team.id.equals(project.teamId)) {
      throw new NotFoundException();
    }
    if (!project.privateRepository) {
      throw badRequest("A token can be validated only against a private project");
    }
    return project;
  }

  private static TokenView tokenView(Team team) {
    return new TokenView(team.tokenSecret != null, team.tokenUpdatedAt, team.tokenUpdatedBy);
  }

  private void checkKey() {
    try {
      cipher.checkKey();
    } catch (CredentialCipher.CredentialException e) {
      throw serviceUnavailable(e.getMessage());
    }
  }

  private TeamView view(Team team) {
    TeamRole role = TeamMember.roleOf(team.id, access.subject());
    return new TeamView(
        team.id,
        team.name,
        access.isAdmin() ? "ADMIN" : roleName(role),
        Project.count("teamId", team.id),
        TeamMember.count("teamId", team.id),
        team.tokenSecret != null);
  }

  private Team visible(Long id) {
    return access.visibleTeam(id);
  }

  private Team managed(Long id) {
    Team team = visible(id);
    if (!access.isAdmin() && TeamMember.roleOf(id, access.subject()) != TeamRole.LEAD) {
      throw new ForbiddenException();
    }
    return membersService.lockTeam(team);
  }

  private String existingSubject(Long id, String username) {
    String subject = subjectOf(username);
    if (subject == null || TeamMember.roleOf(id, subject) == null) {
      throw notFound("'" + username + "' is not a member of this team");
    }
    return subject;
  }

  private String subjectOf(String username) {
    try {
      for (UserDirectory.User user : directory.search(username, 0, 100)) {
        if (username.equalsIgnoreCase(user.username())) {
          return user.id();
        }
      }
      return null;
    } catch (UserDirectory.UnavailableException e) {
      Log.warnf("User lookup failed: %s", e.getMessage());
      throw problem(502, "The user directory is unavailable; try again later");
    }
  }

  private String usernameOf(String subject) {
    return directory.find(subject).map(UserDirectory.User::username).orElse("unknown");
  }

  private static String normalizedUsername(String value) {
    if (value == null || value.isBlank()) {
      throw notFound("The team member was not found");
    }
    return value.trim();
  }

  private static String roleName(TeamRole role) {
    return role == null ? null : role.name();
  }

  private static TeamRole role(String value, TeamRole fallback) {
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

  private static String name(String value) {
    if (value == null || value.isBlank()) {
      throw badRequest("name is required");
    }
    String normalized = value.trim();
    if (!NAME.matcher(normalized).matches()) {
      throw badRequest(NAME_RULE);
    }
    return normalized;
  }

  private static boolean isDuplicate(PersistenceException exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation
          && "23505".equals(violation.getSQLState())
          && ("uk_team_name".equals(violation.getConstraintName())
              || "uk_team_name_lower".equals(violation.getConstraintName())
              || "teammember_team_subject".equals(violation.getConstraintName()))) {
        return true;
      }
    }
    return false;
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

  private static WebApplicationException problem(int status, String message) {
    return new WebApplicationException(
        Response.status(status)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", message))
            .build());
  }

  private static WebApplicationException serviceUnavailable(String message) {
    return problem(503, message);
  }
}
