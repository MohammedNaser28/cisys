/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import java.nio.file.Path;
import java.util.Map;

/**
 * A kind of build report, named by its key under a workflow's {@code reports}. A report runs in
 * cisys after the build's jobs, against the build's checkout; the build's result does not depend on
 * it. Implementations are CDI beans.
 */
public interface ReportType {
  /** The key under {@code reports} that selects this type, such as {@code list}. */
  String type();

  /** Produces one report. */
  Report run(Context context) throws Exception;

  /**
   * What a report runs against: the checkout, the parsed workflow and the report's own entry from
   * the workflow, which always has a {@code name}, and the variables an admin set for the type
   * under Settings &gt; Reports.
   */
  record Context(
      Path checkout,
      Workflow workflow,
      Map<String, String> options,
      Map<String, String> variables) {
    public Context(Path checkout, Workflow workflow, Map<String, String> options) {
      this(checkout, workflow, options, Map.of());
    }
  }

  /** The report's content and how the build page shows it. */
  record Report(Format format, String content) {}

  /** Text is shown as is; HTML is shown in a sandboxed frame without scripts. */
  enum Format {
    TEXT,
    HTML
  }
}
