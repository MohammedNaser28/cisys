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
import java.time.Instant;

/** A named team of users that may be assigned to projects. */
@Entity
public class Team extends PanacheEntity {
  @Column(nullable = false, length = 100, unique = true)
  public String name;

  @Column(nullable = false, length = 255)
  public String ownerSubject;

  /** The team's Git token encrypted with AES-GCM; null when no token is stored. */
  @Column(columnDefinition = "bytea")
  public byte[] tokenSecret;

  /** When the stored token was last set, rotated or cleared. */
  public Instant tokenUpdatedAt;

  /** The subject that last set or rotated the stored token. */
  @Column(length = 255)
  public String tokenUpdatedBy;

  /** When the team was created. */
  @Column(nullable = false)
  public Instant createdAt = Instant.now();
}
