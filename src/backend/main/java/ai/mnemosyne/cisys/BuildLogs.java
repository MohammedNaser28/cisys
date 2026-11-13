/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;

/** Stores each build log as one PostgreSQL large object, through JDBC rather than Hibernate. */
@ApplicationScoped
public class BuildLogs {
  static final int FLUSH_BYTES = 1 << 20;
  static final int READ_BYTES = 1 << 20;

  @Inject DataSource dataSource;

  private volatile long flushIntervalMillis = 5000;
  private volatile boolean failWrites;
  private ScheduledExecutorService flusher;

  @PostConstruct
  void start() {
    flusher =
        Executors.newSingleThreadScheduledExecutor(r -> {
          Thread t = new Thread(r, "cisys-log-flusher");
          t.setDaemon(true);
          return t;
        });
  }

  @PreDestroy
  void stop() {
    flusher.shutdownNow();
  }

  /** For tests. */
  void flushInterval(long millis) {
    flushIntervalMillis = millis;
  }

  /** For tests. */
  void failWrites(boolean fail) {
    failWrites = fail;
  }

  public Writer open(long buildId) {
    long oid =
        QuarkusTransaction.requiringNew()
            .call(() -> {
              try (Connection c = dataSource.getConnection()) {
                Long existing = logOid(c, buildId, true);
                if (existing != null) return existing;
                long created = largeObjects(c).createLO(LargeObjectManager.READWRITE);
                try (PreparedStatement s =
                    c.prepareStatement("update build set logoid = cast(? as oid) where id = ?")) {
                  s.setLong(1, created);
                  s.setLong(2, buildId);
                  s.executeUpdate();
                }
                return created;
              }
            });
    return new Writer(oid);
  }

  /** Returns the storage failure, or null. */
  public Throwable appendLine(long buildId, String text) {
    Writer w;
    try {
      w = open(buildId);
    } catch (RuntimeException e) {
      return e;
    }
    w.line(text);
    w.close();
    return w.failure();
  }

  public void streamTo(long buildId, OutputStream out) throws IOException {
    streamTo(buildId, out, 0, -1);
  }

  /**
   * Streams the stored bytes from {@code offset}, at most {@code limit} of them. A negative limit
   * means no limit. Used by the log endpoints to support partial and tail reads.
   */
  public void streamTo(long buildId, OutputStream out, long offset, long limit) throws IOException {
    if (offset < 0) throw new IllegalArgumentException("offset must not be negative");
    if (limit == 0) return;
    Long oid = logOid(buildId);
    if (oid == null) return;
    long written = 0;
    for (long at = offset; limit < 0 || written < limit; ) {
      int want = (int) Math.min(READ_BYTES, limit < 0 ? READ_BYTES : limit - written);
      byte[] part = read(oid, at, want);
      if (part.length == 0) return;
      out.write(part);
      out.flush();
      at += part.length;
      written += part.length;
    }
  }

  /** Total number of bytes stored for a build, or zero when it has no log yet. */
  public long size(long buildId) {
    Long oid = logOid(buildId);
    if (oid == null) return 0;
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          try (Connection c = dataSource.getConnection()) {
            LargeObject lo = largeObjects(c).open(oid, LargeObjectManager.READ);
            try {
              return lo.size64();
            } finally {
              lo.close();
            }
          }
        });
  }

  /** A resolved byte range of one log. */
  public record Range(long offset, long length) {}

  /**
   * Resolves the optional {@code offset}, {@code limit} and {@code tail} query values against the
   * stored size. {@code tail} wins over {@code offset}; {@code maxBytes} clamps the result when it
   * is not negative. A null limit means "everything that remains". A {@code tail} larger than
   * {@code maxBytes} is reduced to the maximum, so the caller still gets the end of the log.
   */
  public static Range range(long size, Long offset, Long limit, Long tail, long maxBytes) {
    if (offset != null && offset < 0)
      throw new IllegalArgumentException("offset must not be negative");
    if (limit != null && limit < 0)
      throw new IllegalArgumentException("limit must not be negative");
    if (tail != null && tail < 0) throw new IllegalArgumentException("tail must not be negative");
    long start;
    if (tail != null) {
      long wanted = maxBytes >= 0 ? Math.min(tail, maxBytes) : tail;
      start = Math.max(0, size - wanted);
    } else {
      start = offset == null ? 0 : offset;
    }
    long available = Math.max(0, size - start);
    long length = limit == null ? available : Math.min(limit, available);
    if (maxBytes >= 0) length = Math.min(length, maxBytes);
    return new Range(start, length);
  }

  /** For tests. */
  String readAll(long buildId) {
    try (ByteArrayOutputStream all = new ByteArrayOutputStream()) {
      streamTo(buildId, all);
      return all.toString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  Long logOid(long buildId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          try (Connection c = dataSource.getConnection()) {
            return logOid(c, buildId, false);
          }
        });
  }

  private static Long logOid(Connection c, long buildId, boolean lock) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement("select logoid from build where id = ?" + (lock ? " for update" : ""))) {
      s.setLong(1, buildId);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new IllegalArgumentException("No build " + buildId);
        long oid = r.getLong(1);
        return r.wasNull() ? null : oid;
      }
    }
  }

  private static LargeObjectManager largeObjects(Connection c) throws SQLException {
    return c.unwrap(PGConnection.class).getLargeObjectAPI();
  }

  private byte[] read(long oid, long offset, int length) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          try (Connection c = dataSource.getConnection()) {
            LargeObject lo = largeObjects(c).open(oid, LargeObjectManager.READ);
            try {
              lo.seek64(offset, LargeObject.SEEK_SET);
              return lo.read(length);
            } finally {
              lo.close();
            }
          }
        });
  }

  private void writeAtEnd(long oid, byte[] data, int length) {
    if (failWrites) throw new IllegalStateException("Log writes disabled for testing");
    QuarkusTransaction.requiringNew()
        .call(() -> {
          try (Connection c = dataSource.getConnection()) {
            LargeObject lo = largeObjects(c).open(oid, LargeObjectManager.READWRITE);
            try {
              lo.seek64(0, LargeObject.SEEK_END);
              lo.write(data, 0, length);
            } finally {
              lo.close();
            }
          }
          return null;
        });
  }

  /**
   * Never throws: a storage failure is recorded and later output is dropped, so the build is never
   * blocked.
   */
  public final class Writer implements BuildRunner.LogSink, AutoCloseable {
    private final long oid;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean closed;
    private boolean failed;
    private Throwable failure;
    private final ScheduledFuture<?> task;

    private Writer(long oid) {
      this.oid = oid;
      long interval = flushIntervalMillis;
      this.task =
          flusher.scheduleWithFixedDelay(
              this::periodicFlush, interval, interval, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void append(byte[] bytes, int off, int len) {
      while (len > 0 && !closed && !failed) {
        int n = Math.min(len, FLUSH_BYTES - buffer.size());
        buffer.write(bytes, off, n);
        off += n;
        len -= n;
        if (buffer.size() >= FLUSH_BYTES) flush();
      }
    }

    public synchronized void line(String text) {
      byte[] b = (text + "\n").getBytes(StandardCharsets.UTF_8);
      append(b, 0, b.length);
    }

    public synchronized Throwable failure() {
      return failure;
    }

    /** For tests. */
    synchronized int buffered() {
      return buffer.size();
    }

    private synchronized void periodicFlush() {
      if (!closed) flush();
    }

    private void flush() {
      if (failed || buffer.size() == 0) return;
      try {
        writeAtEnd(oid, buffer.toByteArray(), buffer.size());
        buffer.reset();
      } catch (Throwable e) {
        failure = e;
        failed = true;
        buffer.reset();
      }
    }

    @Override
    public void close() {
      task.cancel(false);
      synchronized (this) {
        if (closed) return;
        flush();
        closed = true;
      }
    }
  }
}
