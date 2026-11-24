/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecuteReportTest {
  private static ExecuteReport running(String script) {
    return new ExecuteReport() {
      @Override
      public String type() {
        return "test";
      }

      @Override
      protected List<String> command(Context context) {
        return List.of("sh", "-c", script);
      }

      @Override
      int timeoutSeconds() {
        return 1;
      }
    };
  }

  private static ReportType.Context context(Path checkout) {
    return new ReportType.Context(checkout, null, Map.of());
  }

  @Test
  void aProgramThatPrintsNothingAndNeverEndsTimesOut(@TempDir Path checkout) {
    assertTimeoutPreemptively(
        Duration.ofSeconds(15),
        () -> assertThrows(IOException.class, () -> running("sleep 30").run(context(checkout))));
  }

  @Test
  void aProgramThatPrintsAndEndsIsKept(@TempDir Path checkout) throws Exception {
    assertEquals("hi\n", running("echo hi").run(context(checkout)).content());
  }
}
