/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;

@Entity
public class Project extends PanacheEntity {
  @Column(nullable = false, length = 100, unique = true)
  public String name;

  @Column(nullable = false, length = 2048, unique = true)
  public String repositoryUrl;

  @Column(nullable = false)
  public boolean privateRepository;

  @Column(nullable = false, length = 255)
  public String ownerSubject;
}
