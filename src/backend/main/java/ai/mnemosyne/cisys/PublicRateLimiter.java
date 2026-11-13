/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * A small per-client fixed-window counter for the anonymous endpoints. It lives in this process, so
 * it resets on restart and is not shared between instances; the runner is documented as a
 * single-instance deployment. Expired windows are purged when the map reaches {@code max-clients};
 * if it is still full, one live window is evicted so a new client is never locked out and the
 * memory a flood of addresses can use stays bounded. Expired-window purging is throttled and the
 * eviction itself is constant time, so a full map cannot turn ordinary traffic into a scan of every
 * entry on each request.
 */
@ApplicationScoped
public class PublicRateLimiter {
  @ConfigProperty(name = "cisys.public.rate-limit.requests", defaultValue = "120")
  int requests;

  @ConfigProperty(name = "cisys.public.rate-limit.window-seconds", defaultValue = "60")
  int windowSeconds;

  @ConfigProperty(name = "cisys.public.rate-limit.max-clients", defaultValue = "10000")
  int maxClients;

  private static final long PURGE_INTERVAL_MILLIS = 1_000L;

  private record Window(long start, AtomicInteger count) {}

  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
  private volatile long lastPurge;

  /**
   * Counts one request. Returns zero when it is allowed, otherwise the number of seconds the client
   * should wait before retrying. A non-positive request limit disables the limiter.
   */
  long acquire(String client, long nowMillis) {
    if (requests <= 0) return 0;
    long windowMillis = Math.max(1, windowSeconds) * 1000L;
    purge(nowMillis, windowMillis);
    if (maxClients > 0 && windows.size() >= maxClients && !windows.containsKey(client)) {
      // The map is full of live windows. Evict one and admit the new client: a client that
      // rotates addresses can bypass the limiter either way, so rejecting new clients would
      // only let a flood lock legitimate visitors out. Evicting the first entry the iterator
      // returns keeps this O(1); "oldest" is not important for a fail-open limiter.
      Iterator<String> candidates = windows.keySet().iterator();
      if (candidates.hasNext()) windows.remove(candidates.next());
    }
    Window window =
        windows.compute(
            client,
            (key, current) ->
                current == null || nowMillis - current.start() >= windowMillis
                    ? new Window(nowMillis, new AtomicInteger())
                    : current);
    if (window.count().incrementAndGet() <= requests) return 0;
    long remaining = window.start() + windowMillis - nowMillis;
    return Math.max(1, (remaining + 999) / 1000);
  }

  /** For tests. */
  void reset() {
    windows.clear();
    lastPurge = 0;
  }

  private void purge(long now, long windowMillis) {
    if (maxClients <= 0 || windows.size() < maxClients) return;
    // A full map is checked on every request, so do the O(n) sweep at most once a second.
    if (now - lastPurge < PURGE_INTERVAL_MILLIS) return;
    lastPurge = now;
    windows.entrySet().removeIf(entry -> now - entry.getValue().start() >= windowMillis);
  }
}
