/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS
 *   ECLIPSE PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THE AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;

/** A user assigned to a team, by Keycloak subject. */
@Entity
public class TeamMember extends PanacheEntity {
  @Column(nullable = false)
  public Long teamId;

  @Column(nullable = false, length = 255)
  public String subject;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  public ProjectMember.TeamRole role = ProjectMember.TeamRole.MEMBER;

  @Column(nullable = false)
  public Instant createdAt = Instant.now();

  /** The role {@code subject} holds in the team, or null when they are not a member. */
  public static ProjectMember.TeamRole roleOf(Long teamId, String subject) {
    if (subject == null) {
      return null;
    }
    return TeamMember.<TeamMember>find("teamId = ?1 and subject = ?2", teamId, subject)
        .firstResultOptional()
        .map(member -> member.role)
        .orElse(null);
  }
}
