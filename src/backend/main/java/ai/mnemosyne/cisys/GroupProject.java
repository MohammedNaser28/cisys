/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import ai.mnemosyne.cisys.ProjectMember.TeamRole;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import java.io.Serializable;
import java.util.Objects;

/**
 * A group linked to a project. The link role is what every member of the group gets on that
 * project; the project's LEAD (or a global admin) chooses it. Deleting either side removes the
 * link.
 */
@Entity
@IdClass(GroupProject.Key.class)
public class GroupProject extends PanacheEntityBase {
  /** The composite key: one link per group and project. */
  public static class Key implements Serializable {
    private static final long serialVersionUID = 1L;

    public Long groupId;

    public Long projectId;

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Key key)) {
        return false;
      }
      return Objects.equals(groupId, key.groupId) && Objects.equals(projectId, key.projectId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(groupId, projectId);
    }
  }

  @Id
  @Column(nullable = false)
  public Long groupId;

  @Id
  @Column(nullable = false)
  public Long projectId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  public TeamRole role = TeamRole.MEMBER;

  /** The highest role {@code subject} gets through any group linked to the project. */
  static TeamRole roleOf(Long projectId, String subject) {
    if (subject == null) {
      return null;
    }
    TeamRole best = null;
    for (GroupProject link :
        GroupProject.<GroupProject>list(
            "projectId = ?1 and groupId in"
                + " (select gm.groupId from GroupMember gm where gm.subject = ?2)",
            projectId,
            subject)) {
      best = ProjectRoles.higher(best, link.role);
    }
    return best;
  }
}
