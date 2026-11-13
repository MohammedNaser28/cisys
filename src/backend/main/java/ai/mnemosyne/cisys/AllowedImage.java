/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** A container image that admins have allowed for builds. */
@Entity
public class AllowedImage extends PanacheEntityBase {
  @Id
  @Column(length = 201)
  public String name;

  public static final String PATTERN = "[A-Za-z0-9][A-Za-z0-9_./:@-]{0,200}";

  public static boolean isAllowed(String name) {
    return name != null && findById(name) != null;
  }
}
