/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTING RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import ai.mnemosyne.cisys.ProjectMember.TeamRole;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Shared team mutations and their invariant checks, used by the team API and the user-management
 * API. Every path that writes {@code projectmember} goes through this class, so the rules below
 * apply uniformly:
 *
 * <ul>
 *   <li>a project keeps at least one direct LEAD: removing or demoting the last one answers 409,
 *       including for global admins (as on the team API); team membership does not count;
 *   <li>the project's creator can be demoted or removed only by a global admin.
 * </ul>
 *
 * <p>Callers own the surrounding transaction (Panache joins the JTA transaction of the resource
 * method or of an explicit {@code QuarkusTransaction} block); all project rows touched by an
 * operation are locked with a pessimistic write lock, in ascending project id order, before any
 * membership is read or written, and the whole operation rolls back when a safeguard fails.
 */
@ApplicationScoped
public class ProjectMemberService {

  @Inject ProjectAccess access;
  @Inject AuditService audit;

  /** Locks the given projects in ascending project id order and returns them by id. */
  Map<Long, Project> lockProjects(Collection<Long> ids) {
    Map<Long, Project> locked = new HashMap<>();
    new TreeSet<>(ids).forEach(id -> locked.put(id, lockProject(Project.findById(id))));
    return locked;
  }

  /** Locks one project; 404 when it does not exist. */
  Project lockProject(Project project) {
    if (project == null) {
      throw problem(404, "Unknown project");
    }
    // The caller (or lockProjects) loaded the row before this call, and upgrading the lock does
    // not refresh it, so re-read the row under the lock before its fields drive a decision.
    Panache.getEntityManager().refresh(project, LockModeType.PESSIMISTIC_WRITE);
    return project;
  }

  /** Throws 409 when the project has no other direct LEAD than the one being removed or demoted. */
  void requireAnotherLead(Long projectId) {
    if (ProjectMember.count("projectId = ?1 and role = ?2", projectId, TeamRole.LEAD) <= 1) {
      throw problem(409, "A project must keep at least one LEAD; add another LEAD first");
    }
  }

  /**
   * The project's creator can be demoted or removed only by a global admin: a project LEAD,
   * including the creator themselves, cannot touch the creator's project role. Passing a role of
   * LEAD (a no-op promotion) is always allowed.
   */
  void protectCreator(Project project, ProjectMember member, TeamRole newRole) {
    if (access.isAdmin()) {
      return;
    }
    boolean removal = newRole == null;
    boolean demote = newRole != null && newRole != TeamRole.LEAD;
    if (project.ownerSubject != null
        && project.ownerSubject.equals(member.subject)
        && (removal || demote)) {
      throw problem(403, "Only a global admin can demote or remove the project's creator");
    }
  }

  /**
   * Brings {@code subject}'s memberships inside {@code scope} in line with {@code wanted},
   * preserving the role and creation time of rows that remain wanted.
   *
   * <p>Scope limits the projects the caller may manage: {@code null} means all projects (global
   * admin); otherwise the subject's memberships and the wanted additions are limited to the given
   * ids and any wanted id outside the scope is rejected with 403, matching the Users API.
   *
   * <p>Kept rows are returned untouched; genuinely new rows are created as {@link TeamRole#MEMBER
   * MEMBER}; rows no longer wanted are deleted after the last-LEAD and creator safeguards have
   * passed.
   *
   * <p>All decisions that remove or depend on a row refresh its state from the database while the
   * enclosing lock on its project is held. A plain find/refreshed entity is used so a
   * session-cached row loaded before the lock (or before another transaction committed) cannot
   * drive a stale safeguard check.
   *
   * <p>Residual race: a membership inserted for the subject after this transaction's snapshot is
   * taken and into a project that is not part of this operation survives the operation even if the
   * caller intended to manage it. Closing that window would require locking every project, which is
   * impractical; the inserting transaction is serialized only through its own project lock, so no
   * invariant is violated for the rows this operation does touch.
   */
  void assign(String subject, Collection<Long> scope, Collection<Long> wanted) {
    assign(subject, scope, wanted, null);
  }

  /**
   * As {@link #assign(String, Collection, Collection)}, passing the target's display name for the
   * audit event. Every row inserted or deleted here is recorded once; no caller records membership
   * changes for this path.
   */
  void assign(
      String subject, Collection<Long> scope, Collection<Long> wanted, String targetUsername) {
    TreeSet<Long> wantedSet = new TreeSet<>(wanted);
    TreeSet<Long> scopeSet = scope == null ? null : new TreeSet<>(scope);
    if (!wantedSet.isEmpty() && Project.count("id in ?1", wantedSet) != wantedSet.size()) {
      throw problem(400, "Unknown project");
    }
    if (scopeSet != null && !scopeSet.containsAll(wantedSet)) {
      throw problem(403, "You can assign only your own projects");
    }
    // Rows the subject may affect: current ones in scope, plus the wanted ones.
    TreeSet<Long> affected = new TreeSet<>(wantedSet);
    ProjectMember.<ProjectMember>list("subject", subject).stream()
        .map(m -> m.projectId)
        .filter(id -> scopeSet == null || scopeSet.contains(id))
        .forEach(affected::add);
    // Lock the affected projects in ascending id order before touching any membership.
    Map<Long, Project> locked = lockProjects(affected);
    for (Long projectId : affected) {
      Project project = locked.get(projectId);
      boolean keep = wantedSet.contains(projectId);
      ProjectMember current = current(projectId, subject);
      if (keep) {
        if (current == null) {
          ProjectMember m = new ProjectMember();
          m.projectId = projectId;
          m.subject = subject;
          m.role = TeamRole.MEMBER;
          m.createdAt = java.time.Instant.now();
          m.persist();
          audit.success(
              AuditAction.PROJECT_MEMBER_ADD,
              AuditTargetType.PROJECT_MEMBER,
              subject,
              name(targetUsername, subject),
              project,
              "Added " + name(targetUsername, subject) + " to project " + project.name,
              AuditDetails.of("role", TeamRole.MEMBER.name()));
        }
        // Unchanged assignments keep their role and creation time.
        continue;
      }
      if (current == null) {
        continue;
      }
      protectCreator(project, current, null);
      if (current.role == TeamRole.LEAD) {
        requireAnotherLead(projectId);
      }
      TeamRole removedRole = current.role;
      current.delete();
      audit.success(
          AuditAction.PROJECT_MEMBER_REMOVE,
          AuditTargetType.PROJECT_MEMBER,
          subject,
          name(targetUsername, subject),
          project,
          "Removed " + name(targetUsername, subject) + " from project " + project.name,
          AuditDetails.of("role", removedRole.name()));
    }
  }

  /**
   * Removes every project membership of {@code subject}, enforcing the last-LEAD and creator
   * safeguards for each project. Used by admin user deletion. Deletes nothing when any project
   * check fails.
   *
   * <p>Every row checked or deleted here is refreshed from the database while the lock on its
   * project is held, so a session-cached row loaded before the lock cannot drive a stale safeguard
   * check.
   *
   * <p>Residual race: a membership inserted concurrently into a project that is not part of this
   * call's snapshot (and therefore not locked here) survives the deletion. Fully closing that
   * window would require locking the whole project table or serializing inserts against deletion,
   * which is not done; such a row refers to a directory user that may no longer exist.
   */
  void removeSubject(String subject) {
    removeSubject(subject, null);
  }

  /**
   * As {@link #removeSubject(String)}, passing the target's display name for the audit event. Every
   * deleted row is recorded once.
   */
  void removeSubject(String subject, String targetUsername) {
    List<ProjectMember> snapshot = ProjectMember.list("subject", subject);
    TreeSet<Long> ids = new TreeSet<>();
    snapshot.forEach(m -> ids.add(m.projectId));
    Map<Long, Project> locked = lockProjects(ids);
    for (Long projectId : ids) {
      ProjectMember row = current(projectId, subject);
      if (row == null) {
        // Another transaction removed the row under its project lock; nothing to do.
        continue;
      }
      protectCreator(locked.get(projectId), row, null);
      if (row.role == TeamRole.LEAD) {
        requireAnotherLead(projectId);
      }
    }
    for (Long projectId : ids) {
      ProjectMember row = current(projectId, subject);
      if (row != null) {
        TeamRole removedRole = row.role;
        Project project = locked.get(projectId);
        row.delete();
        audit.success(
            AuditAction.PROJECT_MEMBER_REMOVE,
            AuditTargetType.PROJECT_MEMBER,
            subject,
            name(targetUsername, subject),
            project,
            "Removed " + name(targetUsername, subject) + " from project " + project.name,
            AuditDetails.of("role", removedRole.name()));
      }
    }
  }

  /** A username for display, falling back to the stable subject. */
  private static String name(String username, String subject) {
    return username == null || username.isBlank() ? subject : username;
  }

  /** Finds the subject's row in one project, refreshed from the database (defeats the L1 cache). */
  private static ProjectMember current(Long projectId, String subject) {
    ProjectMember row =
        ProjectMember.find("projectId = ?1 and subject = ?2", projectId, subject).firstResult();
    if (row != null) {
      Panache.getEntityManager().refresh(row);
    }
    return row;
  }

  private static WebApplicationException problem(int status, String message) {
    return new WebApplicationException(
        Response.status(status)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", message))
            .build());
  }
}
