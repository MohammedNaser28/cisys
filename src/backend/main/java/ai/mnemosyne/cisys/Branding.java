/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class Branding {
  @Id public Long id = 1L;

  @Column(nullable = false, length = 80)
  public String name = "cisys";

  @Column(nullable = false, length = 7)
  public String headerColor = "#176a4a";

  @Column(nullable = false, length = 7)
  public String headingColor = "#176a4a";

  @Column(nullable = false, length = 7)
  public String buttonColor = "#176a4a";

  @Column(length = 350000)
  public String logo;

  @Column(length = 350000)
  public String background;
}
