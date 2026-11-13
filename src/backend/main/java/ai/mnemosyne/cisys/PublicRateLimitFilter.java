/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

/**
 * Applies {@link PublicRateLimiter} to the endpoints marked with {@link PublicRateLimited}.
 * Signed-in callers are never counted, so the authenticated build pages keep working during a burst
 * of anonymous traffic. The client is identified by the socket address; behind a reverse proxy
 * configure Quarkus to use the forwarded address, otherwise every visitor shares one bucket.
 */
@Provider
@PublicRateLimited
@Priority(Priorities.AUTHORIZATION)
public class PublicRateLimitFilter implements ContainerRequestFilter {
  @Inject SecurityIdentity identity;
  @Inject PublicRateLimiter limiter;
  @Context HttpServerRequest request;

  @Override
  public void filter(ContainerRequestContext context) {
    // CORS preflight requests are cheap and must not consume a visitor's budget.
    if (context.getMethod().equals("OPTIONS")) return;
    if (!identity.isAnonymous()) return;
    long retryAfter = limiter.acquire(clientAddress(), System.currentTimeMillis());
    if (retryAfter == 0) return;
    context.abortWith(
        Response.status(429)
            .type(MediaType.APPLICATION_JSON)
            .header("Retry-After", retryAfter)
            .entity(Map.of("error", "Too many requests. Try again later."))
            .build());
  }

  private String clientAddress() {
    try {
      return request.remoteAddress() == null ? "unknown" : request.remoteAddress().hostAddress();
    } catch (RuntimeException e) {
      return "unknown";
    }
  }
}
