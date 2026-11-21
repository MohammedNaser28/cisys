/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import ai.mnemosyne.cisys.ProjectMember.TeamRole;

/**
 * The single place that combines a user's direct project membership with the links of the groups
 * they belong to. The effective role is the highest of the two paths.
 */
final class ProjectRoles {
  private ProjectRoles() {}

  /** The effective role of {@code subject} on the project, or null when they have no access. */
  static TeamRole of(Long projectId, String subject) {
    if (projectId == null || subject == null) {
      return null;
    }
    return higher(
        ProjectMember.roleOf(projectId, subject), GroupProject.roleOf(projectId, subject));
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
