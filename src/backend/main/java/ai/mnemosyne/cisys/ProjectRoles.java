/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import ai.mnemosyne.cisys.ProjectMember.TeamRole;

/** The single place that combines direct project and team membership. */
final class ProjectRoles {
  private ProjectRoles() {}

  /** The effective role of {@code subject} on the project, or null when they have no access. */
  static TeamRole of(Long projectId, String subject) {
    return of(projectId == null ? null : Project.findById(projectId), subject);
  }

  /** The effective role of {@code subject} on {@code project}, or null when they have no access. */
  static TeamRole of(Project project, String subject) {
    if (project == null || subject == null) {
      return null;
    }
    TeamRole teamRole = project.teamId == null ? null : TeamMember.roleOf(project.teamId, subject);
    return higher(ProjectMember.roleOf(project.id, subject), teamRole);
  }

  /** The projects where {@code subject} has {@code required} or a higher effective role. */
  static java.util.Set<Long> projectIds(String subject, TeamRole required) {
    if (subject == null || required == null) {
      return java.util.Set.of();
    }
    String query =
        required == TeamRole.LEAD
            ? "select p.id from Project p where"
                + " exists (select 1 from ProjectMember pm where pm.projectId = p.id"
                + " and pm.subject = ?1 and pm.role = ?2)"
                + " or (p.teamId is not null and exists (select 1 from TeamMember tm"
                + " where tm.teamId = p.teamId and tm.subject = ?1 and tm.role = ?2))"
            : "select p.id from Project p where"
                + " exists (select 1 from ProjectMember pm where pm.projectId = p.id"
                + " and pm.subject = ?1)"
                + " or (p.teamId is not null and exists (select 1 from TeamMember tm"
                + " where tm.teamId = p.teamId and tm.subject = ?1))";
    java.util.List<Long> rows =
        required == TeamRole.LEAD
            ? Project.getEntityManager()
                .createQuery(query, Long.class)
                .setParameter(1, subject)
                .setParameter(2, TeamRole.LEAD)
                .getResultList()
            : Project.getEntityManager()
                .createQuery(query, Long.class)
                .setParameter(1, subject)
                .getResultList();
    return new java.util.TreeSet<>(rows);
  }

  /** The higher of two optional roles, preferring {@code first} on a tie. */
  static TeamRole higher(TeamRole first, TeamRole second) {
    if (first == null) {
      return second;
    }
    if (second == null) {
      return first;
    }
    return level(first) >= level(second) ? first : second;
  }

  /** The ordering used to compare project roles. */
  static int level(TeamRole role) {
    return role == TeamRole.LEAD ? 2 : 1;
  }
}
