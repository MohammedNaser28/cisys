/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The base of report types that run a program in the checkout and show what it prints. A subclass
 * says which program, from the report's options and the admin's variables.
 */
public abstract class ExecuteReport implements ReportType {
  /** The longest the program runs, in seconds. */
  static final int TIMEOUT_SECONDS = 600;

  /** The most output kept, in bytes; the program is not stopped, the rest is dropped. */
  static final int MAX_OUTPUT = 1024 * 1024;

  /** The program and its arguments. */
  protected abstract List<String> command(Context context);

  /** The longest the program runs, in seconds. */
  int timeoutSeconds() {
    return TIMEOUT_SECONDS;
  }

  @Override
  public Report run(Context context) throws IOException, InterruptedException {
    Process p =
        new ProcessBuilder(command(context))
            .directory(context.checkout().toFile())
            .redirectErrorStream(true)
            .start();
    // The program gets no input; one that waits for a terminal would otherwise run until the
    // timeout.
    p.getOutputStream().close();
    // Read on another thread, so that the timeout also covers a program that prints nothing and
    // never ends. Closing the stream after MAX_OUTPUT makes a program that still writes fail, so
    // it cannot block on a full pipe.
    CompletableFuture<byte[]> output = new CompletableFuture<>();
    Thread.startVirtualThread(() -> {
      try (InputStream in = p.getInputStream()) {
        output.complete(in.readNBytes(MAX_OUTPUT));
      } catch (IOException e) {
        output.completeExceptionally(e);
      }
    });
    if (!p.waitFor(timeoutSeconds(), TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IOException("timed out after " + timeoutSeconds() + " seconds");
    }
    byte[] out;
    try {
      out = output.get(10, TimeUnit.SECONDS);
    } catch (ExecutionException | TimeoutException e) {
      throw new IOException("could not read the output", e);
    }
    if (p.exitValue() != 0) {
      throw new IOException("exited with " + p.exitValue());
    }
    return new Report(Format.TEXT, new String(out, StandardCharsets.UTF_8));
  }
}
