/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

/** The response shape shared by the authenticated and public build-log endpoints. */
final class LogResponses {
  private LogResponses() {}

  /**
   * Streams the resolved range as plain text. The headers let a client continue from the end of
   * this response without downloading the whole log again.
   */
  static Response stream(BuildLogs logs, long buildId, long size, BuildLogs.Range range) {
    return Response.ok(
            (StreamingOutput) out -> logs.streamTo(buildId, out, range.offset(), range.length()))
        .type("text/plain; charset=UTF-8")
        .header("X-Log-Size", size)
        .header("X-Log-Offset", range.offset())
        .header("X-Log-Length", range.length())
        .build();
  }
}
