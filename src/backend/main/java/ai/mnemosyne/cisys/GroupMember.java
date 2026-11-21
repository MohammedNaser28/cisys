/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;

/**
 * A user assigned to a group, by Keycloak subject. The group role only controls who manages the
 * group's membership; it grants no project role by itself. Deleting the group removes the row.
 */
@Entity
public class GroupMember extends PanacheEntity {
  /**
   * The group roles: {@code MEMBER} may see the group; {@code LEAD} also manages its membership.
   */
  public enum GroupRole {
    MEMBER,
    LEAD
  }

  @Column(nullable = false)
  public Long groupId;

  @Column(nullable = false, length = 255)
  public String subject;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  public GroupRole role = GroupRole.MEMBER;

  @Column(nullable = false)
  public Instant createdAt = Instant.now();

  /** The role {@code subject} holds in the group, or null when they are not a member. */
  static GroupRole roleOf(Long groupId, String subject) {
    if (subject == null) {
      return null;
    }
    return GroupMember.<GroupMember>find("groupId = ?1 and subject = ?2", groupId, subject)
        .firstResultOptional()
        .map(m -> m.role)
        .orElse(null);
  }
}
