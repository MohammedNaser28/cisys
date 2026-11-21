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
import java.time.Instant;

/**
 * A named group of users. The group's own membership roles only decide who may manage the group;
 * project access comes from the group's links to projects in {@link GroupProject}.
 */
@Entity
public class UserGroup extends PanacheEntity {
  @Column(nullable = false, length = 100, unique = true)
  public String name;

  @Column(nullable = false)
  public Instant createdAt = Instant.now();
}
