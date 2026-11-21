/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import ai.mnemosyne.cisys.UserDirectory.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Users and the projects they are assigned to. Admins manage every user; project LEADs manage the
 * members of the projects where they hold the LEAD role; members have no access.
 */
@Path("/api/users")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserResource {
  static final int DEFAULT_PAGE = 50;
  static final int MAX_PAGE = 100;
  private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._@-]{0,99}");
  private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+");

  @Inject SecurityIdentity identity;
  @Inject UserDirectory directory;
  @Inject ProjectMemberService memberService;
  @Inject AuditService audit;
  @Inject TeamMemberService teamMemberService;

  /**
   * A user to create or change; omitted project membership leaves existing assignments unchanged.
   */
  public record Input(
      String username,
      String email,
      String firstName,
      String lastName,
      String password,
      String role,
      Boolean enabled,
      List<Long> projectIds) {}

  /** A temporary password. */
  public record Password(String password) {}

  /** A project a user is, or can be, assigned to. */
  public record ProjectRef(Long id, String name) {}

  /**
   * A user. {@code manageable} tells whether the caller may change it; a lead only sees the
   * projects in their own scope.
   */
  public record View(
      String id,
      String username,
      String email,
      String firstName,
      String lastName,
      String role,
      boolean enabled,
      boolean manageable,
      List<ProjectRef> projects) {}

  /** One page of users and how many match in all. */
  public record Page(List<View> users, int total) {}

  /**
   * The signed-in admin or lead, with the projects the caller may manage (admin, or project LEAD).
   */
  private record Caller(String subject, boolean admin, Set<Long> projects) {
    boolean inScope(Long projectId) {
      return admin || projects.contains(projectId);
    }
  }

  /** The roles of every user that has one, read once per request. */
  private record Roles(Set<String> admins, Set<String> leads, Set<String> members) {
    String of(String id) {
      if (admins.contains(id)) {
        return "admin";
      }
      if (leads.contains(id)) {
        return "lead";
      }
      if (members.contains(id)) {
        return "member";
      }
      return null;
    }
  }

  /**
   * Users matching {@code search}, optionally only those with {@code role} or assigned to {@code
   * project}. Leads see the users assigned to their projects and, when searching, every member.
   */
  @GET
  public Page list(
      @QueryParam("search") String search,
      @QueryParam("role") String role,
      @QueryParam("project") Long project,
      @QueryParam("first") Integer first,
      @QueryParam("max") Integer max) {
    Caller caller = caller();
    int from = first == null ? 0 : Math.max(0, first);
    int size = max == null ? DEFAULT_PAGE : Math.min(MAX_PAGE, Math.max(1, max));
    if (role != null && !role.isBlank() && !UserDirectory.ROLES.contains(role)) {
      throw error(400, "Unknown role");
    }
    if (project != null && !caller.inScope(project)) {
      throw new NotFoundException();
    }
    String wantedRole = role == null || role.isBlank() ? null : role;
    Roles roles = roles();
    if (caller.admin() && wantedRole == null && project == null) {
      List<User> page = directory.search(search, from, size);
      return new Page(views(caller, roles, page), directory.count(search));
    }
    Map<String, User> candidates = new LinkedHashMap<>();
    if (project == null && caller.admin()) {
      directory.inRole(wantedRole).forEach(u -> candidates.put(u.id(), u));
    } else {
      Collection<Long> scope = project == null ? caller.projects() : List.of(project);
      for (String subject : ProjectMember.subjects(scope)) {
        directory.find(subject).ifPresent(u -> candidates.put(u.id(), u));
      }
      // A lead adds members from other projects by searching for them.
      if (project == null && !caller.admin() && search != null && !search.isBlank()) {
        directory.search(search, 0, MAX_PAGE).stream()
            .filter(u -> "member".equals(roles.of(u.id())))
            .forEach(u -> candidates.putIfAbsent(u.id(), u));
      }
    }
    List<User> matching =
        candidates.values().stream()
            .filter(u -> matches(u, search))
            .filter(u -> wantedRole == null || wantedRole.equals(roles.of(u.id())))
            .sorted(Comparator.comparing(User::username))
            .toList();
    List<User> page = matching.stream().skip(from).limit(size).toList();
    return new Page(views(caller, roles, page), matching.size());
  }

  /** The projects the caller may assign users to. */
  @GET
  @Path("/projects")
  public List<ProjectRef> projects() {
    Caller caller = caller();
    if (!caller.admin() && caller.projects().isEmpty()) {
      return List.of();
    }
    List<Project> projects =
        caller.admin()
            ? Project.list("order by name")
            : Project.list("id in ?1 order by name", caller.projects());
    return projects.stream().map(p -> new ProjectRef(p.id, p.name)).toList();
  }

  /**
   * Creates a user with a temporary password. Leads create members only, assigned to at least one
   * of their projects.
   */
  @POST
  @Audited(action = AuditAction.USER_CREATE, target = AuditTargetType.USER, summary = "Create user")
  public Response create(Input input) {
    Caller caller = caller();
    if (input == null) {
      throw error(400, "A username and a password are required");
    }
    String role = input.role() == null || input.role().isBlank() ? "member" : input.role();
    if (!UserDirectory.ROLES.contains(role)) {
      throw error(400, "Unknown role");
    }
    if (!caller.admin() && !role.equals("member")) {
      throw new ForbiddenException();
    }
    Set<Long> projects = projectIds(input.projectIds());
    if (!caller.admin()) {
      if (projects.isEmpty()) {
        throw error(400, "Choose at least one project");
      }
      if (!caller.projects().containsAll(projects)) {
        throw new ForbiddenException();
      }
    }
    String username = username(input.username());
    String password = password(input.password());
    User user =
        new User(
            null,
            username,
            email(input.email()),
            name(input.firstName()),
            name(input.lastName()),
            true);
    String id = directory.create(user, password);
    try {
      directory.setRole(id, role);
      // New assignments come from the shared safe path; existing rows cannot exist yet, so this
      // cannot demote anyone.
      QuarkusTransaction.requiringNew()
          .run(() ->
              memberService.assign(
                  id, caller.admin() ? null : caller.projects(), projects, username));
    } catch (RuntimeException e) {
      // Leave no half-created user behind.
      try {
        directory.delete(id);
      } catch (RuntimeException ignored) {
        // The original failure is the one to report.
      }
      throw e;
    }
    User created = directory.find(id).orElseThrow(NotFoundException::new);
    audit.bestEffortSuccess(
        AuditAction.USER_CREATE,
        AuditTargetType.USER,
        created.id(),
        created.username(),
        (Long) null,
        (String) null,
        "Created user " + created.username(),
        AuditDetails.of("role", role, "projectIds", projects));
    return Response.status(201).entity(views(caller, roles(), List.of(created)).get(0)).build();
  }

  /**
   * Changes a user. Admins change everything but the username; leads change only which of their
   * projects a member is assigned to.
   */
  @PUT
  @Path("/{id}")
  @Audited(action = AuditAction.USER_UPDATE, target = AuditTargetType.USER, summary = "Update user")
  public View update(@PathParam("id") String id, Input input) {
    Caller caller = caller();
    if (input == null) {
      throw error(400, "Nothing to change");
    }
    User user = directory.find(id).orElseThrow(NotFoundException::new);
    String current = directory.role(id);
    if (!caller.admin()) {
      // Only members: a user without a cisys role may still hold Keycloak admin rights.
      if (!"member".equals(current)) {
        throw new NotFoundException();
      }
      if (input.projectIds() != null) {
        Set<Long> wanted = projectIds(input.projectIds());
        if (!caller.projects().containsAll(wanted)) {
          throw new ForbiddenException();
        }
        QuarkusTransaction.requiringNew()
            .run(() -> memberService.assign(id, caller.projects(), wanted, user.username()));
      }
      return views(caller, roles(), List.of(user)).get(0);
    }
    String role = input.role() == null || input.role().isBlank() ? current : input.role();
    if (role != null && !UserDirectory.ROLES.contains(role)) {
      throw error(400, "Unknown role");
    }
    boolean enabled = input.enabled() == null ? user.enabled() : input.enabled();
    if (id.equals(caller.subject()) && (!"admin".equals(role) || !enabled)) {
      throw error(400, "You cannot remove your own admin access");
    }
    String email = input.email() == null ? user.email() : email(input.email());
    String firstName = input.firstName() == null ? user.firstName() : name(input.firstName());
    String lastName = input.lastName() == null ? user.lastName() : name(input.lastName());
    // Memberships are updated first, in one transaction, so a safeguard failure (for example a
    // last-LEAD conflict) leaves both the membership rows and the directory untouched. The
    // directory is external to the database, though: if a directory call fails after this
    // commits, assignments stay updated while the profile is not (retrying re-applies
    // idempotently).
    if (input.projectIds() != null) {
      Set<Long> wanted = projectIds(input.projectIds());
      QuarkusTransaction.requiringNew()
          .run(() -> memberService.assign(id, null, wanted, user.username()));
    }
    User changed = new User(id, user.username(), email, firstName, lastName, enabled);
    directory.update(changed);
    if (role != null && !role.equals(current)) {
      directory.setRole(id, role);
    }
    List<String> fields = new java.util.ArrayList<>();
    if (!Objects.equals(user.email(), email)) {
      fields.add("email");
    }
    if (!Objects.equals(user.firstName(), firstName)) {
      fields.add("firstName");
    }
    if (!Objects.equals(user.lastName(), lastName)) {
      fields.add("lastName");
    }
    if (!Objects.equals(current, role)) {
      fields.add("role");
    }
    if (user.enabled() != enabled) {
      fields.add("enabled");
    }
    if (input.projectIds() != null) {
      fields.add("projects");
    }
    if (!fields.isEmpty()) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("changed", fields);
      if (!Objects.equals(current, role)) {
        details.put("roleBefore", current);
        details.put("roleAfter", role);
      }
      if (user.enabled() != enabled) {
        details.put("enabledBefore", user.enabled());
        details.put("enabledAfter", enabled);
      }
      audit.bestEffortSuccess(
          AuditAction.USER_UPDATE,
          AuditTargetType.USER,
          changed.id(),
          changed.username(),
          (Long) null,
          (String) null,
          "Updated user " + changed.username(),
          details);
    }
    return views(caller, roles(), List.of(changed)).get(0);
  }

  /**
   * Sets a temporary password. Admins for anyone; leads for the members assigned to their projects.
   */
  @PUT
  @Path("/{id}/password")
  @Audited(
      action = AuditAction.USER_PASSWORD_RESET,
      target = AuditTargetType.USER,
      summary = "Reset user password")
  public Response password(@PathParam("id") String id, Password input) {
    Caller caller = caller();
    User user = directory.find(id).orElseThrow(NotFoundException::new);
    if (!caller.admin()) {
      if (!"member".equals(directory.role(id))
          || !ProjectMember.subjects(caller.projects()).contains(id)) {
        throw new NotFoundException();
      }
    }
    directory.setPassword(id, password(input == null ? null : input.password()));
    audit.bestEffortSuccess(
        AuditAction.USER_PASSWORD_RESET,
        AuditTargetType.USER,
        user.id(),
        user.username(),
        (Long) null,
        (String) null,
        "Reset the password of " + user.username(),
        AuditDetails.of());
    return Response.noContent().build();
  }

  /** Deletes a user and its assignments. Admins only, and not themselves. */
  @DELETE
  @Path("/{id}")
  @Audited(action = AuditAction.USER_DELETE, target = AuditTargetType.USER, summary = "Delete user")
  public Response delete(@PathParam("id") String id) {
    Caller caller = caller();
    if (!caller.admin()) {
      throw new ForbiddenException();
    }
    if (id.equals(caller.subject())) {
      throw error(400, "You cannot delete yourself");
    }
    User user = directory.find(id).orElseThrow(NotFoundException::new);
    String role = directory.role(id);
    // Team rows are locked before project rows, the order documented on TeamMemberService; the
    // reverse would deadlock against a concurrent team attach. Memberships are removed first with
    // the last-LEAD safeguards; the directory is external, so if deleting the user fails, the user
    // still exists without memberships and can be deleted on retry. The reverse order could strand
    // projects without a LEAD.
    QuarkusTransaction.requiringNew()
        .run(() -> {
          teamMemberService.removeSubject(id, user.username());
          memberService.removeSubject(id, user.username());
        });
    directory.delete(id);
    audit.bestEffortSuccess(
        AuditAction.USER_DELETE,
        AuditTargetType.USER,
        user.id(),
        user.username(),
        (Long) null,
        (String) null,
        "Deleted user " + user.username(),
        AuditDetails.of("role", role));
    return Response.noContent().build();
  }

  /** The directory's own reason, such as a password policy failure. */
  @ServerExceptionMapper
  Response rejected(UserDirectory.RejectedException e) {
    return error(400, e.getMessage()).getResponse();
  }

  /** The username or email is taken. */
  @ServerExceptionMapper
  Response conflict(UserDirectory.ConflictException e) {
    return error(409, e.getMessage()).getResponse();
  }

  /** Keycloak is down or refused the service account. */
  @ServerExceptionMapper
  Response unavailable(UserDirectory.UnavailableException e) {
    return error(502, "Users cannot be managed right now").getResponse();
  }

  private Caller caller() {
    if (!(identity.getPrincipal() instanceof JsonWebToken jwt) || jwt.getSubject() == null) {
      throw new NotAuthorizedException("Bearer");
    }
    String subject = jwt.getSubject();
    if (identity.hasRole("admin")) {
      return new Caller(subject, true, Set.of());
    }
    if (!identity.hasRole("lead")) {
      throw new ForbiddenException();
    }
    // Shared effective project LEAD rule, as in ProjectAccess.
    return new Caller(
        subject, false, ProjectRoles.projectIds(subject, ProjectMember.TeamRole.LEAD));
  }

  private Roles roles() {
    return new Roles(ids("admin"), ids("lead"), ids("member"));
  }

  private Set<String> ids(String role) {
    return directory.inRole(role).stream().map(User::id).collect(Collectors.toSet());
  }

  private List<View> views(Caller caller, Roles roles, List<User> users) {
    if (users.isEmpty()) {
      return List.of();
    }
    List<String> ids = users.stream().map(User::id).toList();
    Map<String, List<Long>> memberships = new HashMap<>();
    ProjectMember.<ProjectMember>list("subject in ?1", ids)
        .forEach(
            m -> memberships.computeIfAbsent(m.subject, k -> new ArrayList<>()).add(m.projectId));
    Set<Long> projectIds = new TreeSet<>();
    memberships.values().forEach(projectIds::addAll);
    Map<Long, String> names = new HashMap<>();
    if (!projectIds.isEmpty()) {
      Project.<Project>list("id in ?1", projectIds).forEach(p -> names.put(p.id, p.name));
    }
    List<View> views = new ArrayList<>();
    for (User u : users) {
      String role = roles.of(u.id());
      List<ProjectRef> projects =
          memberships.getOrDefault(u.id(), List.of()).stream()
              .filter(caller::inScope)
              .filter(names::containsKey)
              .map(p -> new ProjectRef(p, names.get(p)))
              .sorted(Comparator.comparing(ProjectRef::name))
              .toList();
      boolean manageable = caller.admin() || "member".equals(role);
      views.add(
          new View(
              u.id(),
              u.username(),
              u.email(),
              u.firstName(),
              u.lastName(),
              role,
              u.enabled(),
              manageable,
              projects));
    }
    return views;
  }

  private static boolean matches(User u, String search) {
    if (search == null || search.isBlank()) {
      return true;
    }
    String s = search.strip().toLowerCase(Locale.ROOT);
    return contains(u.username(), s)
        || contains(u.email(), s)
        || contains(u.firstName(), s)
        || contains(u.lastName(), s);
  }

  private static boolean contains(String value, String search) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(search);
  }

  /** The ids, each of an existing project. */
  private static Set<Long> projectIds(List<Long> ids) {
    if (ids == null) {
      return Set.of();
    }
    Set<Long> set = new TreeSet<>(ids.stream().filter(Objects::nonNull).toList());
    if (!set.isEmpty() && Project.count("id in ?1", set) != set.size()) {
      throw error(400, "Unknown project");
    }
    return set;
  }

  private static String username(String value) {
    String username = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    if (!USERNAME.matcher(username).matches()) {
      throw error(
          400,
          "Usernames use up to 100 letters, digits, '.', '_', '@' and '-', and start with a letter"
              + " or digit");
    }
    return username;
  }

  private static String email(String value) {
    String email = Optional.ofNullable(value).map(String::strip).orElse("");
    if (email.isEmpty()) {
      return null;
    }
    if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
      throw error(400, "Invalid email");
    }
    return email;
  }

  private static String name(String value) {
    String name = Optional.ofNullable(value).map(String::strip).orElse("");
    if (name.length() > 100) {
      throw error(400, "Names are up to 100 characters");
    }
    return name.isEmpty() ? null : name;
  }

  private static String password(String value) {
    if (value == null || value.isBlank() || value.length() > 255) {
      throw error(400, "A password of up to 255 characters is required");
    }
    return value;
  }

  private static WebApplicationException error(int status, String message) {
    return new WebApplicationException(
        Response.status(status)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", message))
            .build());
  }
}
