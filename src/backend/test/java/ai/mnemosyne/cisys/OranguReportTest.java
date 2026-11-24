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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The orangu report, run against a fake executable; the real orangu is never started. */
class OranguReportTest {
  @TempDir Path dir;

  private Path fake(String body) throws IOException {
    Path script = dir.resolve("orangu");
    Files.writeString(script, "#!/bin/sh\n" + body + "\n");
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
    return script;
  }

  private ReportType.Context context(Path checkout, Path tool, Map<String, String> options) {
    return new ReportType.Context(
        checkout, null, options, tool == null ? Map.of() : Map.of("path", tool.toString()));
  }

  @Test
  void runsTheConfiguredPathHeadlessInTheCheckout() throws Exception {
    Path checkout = Files.createDirectory(dir.resolve("checkout"));
    Path tool = fake("echo \"$1|$2\"; pwd");
    ReportType.Report r =
        new OranguReport()
            .run(context(checkout, tool, Map.of("name", "Review", "prompt", "/auto_review")));
    assertEquals(ReportType.Format.TEXT, r.format());
    String[] lines = r.content().split("\n");
    assertEquals("-p|/auto_review", lines[0]);
    assertEquals(checkout.toRealPath(), Path.of(lines[1]).toRealPath());
  }

  @Test
  void aFailingToolIsAnError() throws Exception {
    Path tool = fake("echo no >&2; exit 3");
    assertThrows(
        IOException.class,
        () -> new OranguReport().run(context(dir, tool, Map.of("name", "R", "prompt", "x"))));
  }

  @Test
  void aToolWaitingForInputDoesNotHang() throws Exception {
    Path tool = fake("cat; echo done");
    assertEquals(
        "done\n",
        new OranguReport().run(context(dir, tool, Map.of("name", "R", "prompt", "x"))).content());
  }

  @Test
  void needsAPrompt() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new OranguReport().command(context(dir, null, Map.of("name", "R"))));
  }

  @Test
  void usesOranguFromThePathWithoutAVariable() {
    assertEquals(
        java.util.List.of("orangu", "-p", "/auto_review"),
        new OranguReport()
            .command(context(dir, null, Map.of("name", "R", "prompt", "/auto_review"))));
  }
}
