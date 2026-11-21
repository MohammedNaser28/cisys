/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS
 *   ECLIPSE PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THE AGREEMENT.
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
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Shared team-membership mutations, invariants, and pessimistic locking. Compound mutations lock
 * team rows first and project rows second; callers must preserve that order.
 *
 * <p>Every path that writes {@code teammember} goes through this class, so the last-LEAD and
 * creator safeguards apply uniformly, including to admin user deletion.
 */
@ApplicationScoped
public class TeamMemberService {
  @Inject ProjectAccess access;
  @Inject AuditService audit;

  /** Locks the given teams in ascending id order and returns them by id. */
  Map<Long, Team> lockTeams(Collection<Long> ids) {
    Map<Long, Team> locked = new HashMap<>();
    new TreeSet<>(ids).forEach(id -> locked.put(id, lockTeam(Team.findById(id))));
    return locked;
  }

  /** Locks one team; 404 when it does not exist. */
  Team lockTeam(Team team) {
    if (team == null) {
      throw problem(404, "Unknown team");
    }
    // The callers load the row before this call, and upgrading the lock does not refresh it, so
    // re-read the row under the lock before its fields drive a decision.
    Panache.getEntityManager().refresh(team, LockModeType.PESSIMISTIC_WRITE);
    return team;
  }

  /** Throws 409 when the team has no other LEAD than the one being removed or demoted. */
  void requireAnotherLead(Long teamId) {
    if (TeamMember.count("teamId = ?1 and role = ?2", teamId, TeamRole.LEAD) <= 1) {
      throw problem(409, "A team must keep at least one LEAD; add another LEAD first");
    }
  }

  /**
   * Protects a team's creator from demotion or removal by anyone except a global admin.
   *
   * @param team the locked team
   * @param member the member being changed
   * @param newRole the requested role, or null for removal
   */
  void protectCreator(Team team, TeamMember member, TeamRole newRole) {
    if (team.ownerSubject == null || !team.ownerSubject.equals(member.subject)) {
      return;
    }
    if (access.isAdmin()) {
      return;
    }
    boolean removal = newRole == null;
    boolean demote = newRole != null && newRole != TeamRole.LEAD;
    if (team.ownerSubject != null
        && team.ownerSubject.equals(member.subject)
        && (removal || demote)) {
      throw problem(403, "Only a global admin can demote or remove the team's creator");
    }
  }

  /** Adds a member while holding the team lock. */
  TeamMember add(Long teamId, String subject, TeamRole role) {
    Team team = lockTeam(Team.findById(teamId));
    if (TeamMember.roleOf(team.id, subject) != null) {
      throw problem(409, "The user is already a member of this team");
    }
    TeamMember member = new TeamMember();
    member.teamId = team.id;
    member.subject = subject;
    member.role = role == null ? TeamRole.MEMBER : role;
    member.createdAt = Instant.now();
    member.persistAndFlush();
    return member;
  }

  /** Removes all memberships while the team row is locked, for team deletion. */
  void removeAll(Long teamId) {
    Team team = lockTeam(Team.findById(teamId));
    TeamMember.delete("teamId", team.id);
  }

  /** Changes a member's role while holding the team lock. */
  void changeRole(Long teamId, String subject, TeamRole newRole) {
    Team team = lockTeam(Team.findById(teamId));
    TeamMember member = current(team.id, subject);
    if (member == null) {
      throw problem(404, "The user is not a member of this team");
    }
    protectCreator(team, member, newRole);
    if (member.role == TeamRole.LEAD && newRole != TeamRole.LEAD) {
      requireAnotherLead(team.id);
    }
    member.role = newRole;
  }

  /** Removes a member while holding the team lock. */
  void remove(Long teamId, String subject) {
    Team team = lockTeam(Team.findById(teamId));
    TeamMember member = current(team.id, subject);
    if (member == null) {
      throw problem(404, "The user is not a member of this team");
    }
    protectCreator(team, member, null);
    if (member.role == TeamRole.LEAD) {
      requireAnotherLead(team.id);
    }
    member.delete();
  }

  /**
   * Removes every team membership of {@code subject}, enforcing the creator and lead safeguards.
   * Writes one {@code team.member.remove} event per deleted membership, with the caller (the admin
   * performing the user deletion) as the actor, exactly as the project counterpart does.
   */
  void removeSubject(String subject, String targetUsername) {
    List<TeamMember> snapshot = TeamMember.list("subject", subject);
    TreeSet<Long> ids = new TreeSet<>();
    snapshot.forEach(member -> ids.add(member.teamId));
    Map<Long, Team> locked = lockTeams(ids);
    for (Long teamId : ids) {
      TeamMember member = current(teamId, subject);
      if (member == null || !locked.containsKey(teamId)) {
        continue;
      }
      protectCreator(locked.get(teamId), member, null);
      if (member.role == TeamRole.LEAD) {
        requireAnotherLead(teamId);
      }
    }
    for (Long teamId : ids) {
      TeamMember member = current(teamId, subject);
      if (member != null) {
        TeamRole removedRole = member.role;
        Team team = locked.get(teamId);
        member.delete();
        audit.success(
            AuditAction.TEAM_MEMBER_REMOVE,
            AuditTargetType.TEAM_MEMBER,
            subject,
            name(targetUsername, subject),
            (Project) null,
            "Removed " + name(targetUsername, subject) + " from team " + team.name,
            AuditDetails.of("teamId", teamId, "role", removedRole.name()));
      }
    }
  }

  /** A username for display, falling back to the stable subject. */
  private static String name(String username, String subject) {
    return username == null || username.isBlank() ? subject : username;
  }

  private static TeamMember current(Long teamId, String subject) {
    TeamMember member =
        TeamMember.find("teamId = ?1 and subject = ?2", teamId, subject).firstResult();
    if (member != null) {
      Panache.getEntityManager().refresh(member);
    }
    return member;
  }

  private static WebApplicationException problem(int status, String message) {
    return new WebApplicationException(
        Response.status(status)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", message))
            .build());
  }
}
