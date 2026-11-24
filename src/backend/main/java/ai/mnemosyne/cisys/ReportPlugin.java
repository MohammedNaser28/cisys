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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How an admin configured a report type under Settings &gt; Reports: the {@link ReportType} class
 * that runs it and the variables it needs, such as the path to a tool.
 */
@Entity
public class ReportPlugin extends PanacheEntity {
  /** The report type's key under a workflow's {@code reports}. */
  @Column(nullable = false, length = 50, unique = true)
  public String type;

  @Column(nullable = false)
  public String className;

  /** One {@code key=value} per line. */
  @Column(nullable = false, columnDefinition = "text")
  public String variables = "";

  /** The variables as a map; a line without {@code =} is ignored. */
  Map<String, String> variableMap() {
    Map<String, String> m = new LinkedHashMap<>();
    for (String line : variables.split("\n")) {
      int i = line.indexOf('=');
      if (i > 0) {
        m.put(line.substring(0, i).strip(), line.substring(i + 1).strip());
      }
    }
    return m;
  }
}
