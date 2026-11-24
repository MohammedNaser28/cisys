/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * The {@code orangu} report: runs {@code orangu -p "<prompt>"} headless in the checkout and keeps
 * what it prints, such as the markdown of a {@code /auto_review}. The path to orangu is the admin's
 * {@code path} variable under Settings &gt; Reports; without it {@code orangu} is looked up on the
 * PATH of cisys.
 */
@ApplicationScoped
public class OranguReport extends ExecuteReport {
  @Override
  public String type() {
    return "orangu";
  }

  @Override
  protected List<String> command(Context context) {
    String prompt = context.options().get("prompt");
    if (prompt == null || prompt.isBlank()) {
      throw new IllegalArgumentException("the orangu report needs a prompt");
    }
    String path = context.variables().get("path");
    return List.of(path == null || path.isBlank() ? "orangu" : path, "-p", prompt);
  }
}
