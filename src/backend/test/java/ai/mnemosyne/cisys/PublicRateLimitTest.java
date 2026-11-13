/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PublicRateLimitTest {
  @Inject PublicRateLimiter limiter;
  int requests, windowSeconds, maxClients;

  @BeforeEach
  void save() {
    limiter = io.quarkus.arc.ClientProxy.unwrap(limiter);
    requests = limiter.requests;
    windowSeconds = limiter.windowSeconds;
    maxClients = limiter.maxClients;
    limiter.reset();
  }

  @AfterEach
  void restore() {
    limiter.requests = requests;
    limiter.windowSeconds = windowSeconds;
    limiter.maxClients = maxClients;
    limiter.reset();
  }

  @Test
  void anonymousRequestsAreLimited() {
    limiter.requests = 2;
    limiter.windowSeconds = 60;
    given().get("/api/stats/public").then().statusCode(200);
    given().get("/api/stats/public").then().statusCode(200);
    given()
        .get("/api/stats/public")
        .then()
        .statusCode(429)
        .header("Retry-After", notNullValue())
        .body("error", equalTo("Too many requests. Try again later."));
  }

  @Test
  void projectPagesShareTheLimit() {
    limiter.requests = 1;
    limiter.windowSeconds = 60;
    given().get("/api/project-pages/does-not-exist").then().statusCode(404);
    given().get("/api/project-pages/does-not-exist").then().statusCode(429);
  }

  @Test
  void branchListingSharesTheLimit() {
    limiter.requests = 1;
    limiter.windowSeconds = 60;
    given().get("/api/project-pages/does-not-exist/branches").then().statusCode(404);
    given().get("/api/project-pages/does-not-exist/branches").then().statusCode(429);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  void signedInRequestsAreNotLimited() {
    limiter.requests = 1;
    limiter.windowSeconds = 60;
    for (int i = 0; i < 5; i++) given().get("/api/stats/public").then().statusCode(200);
  }

  @Test
  void aFullClientMapEvictsAWindowInsteadOfRejectingANewClient() {
    limiter.requests = 1;
    limiter.windowSeconds = 60;
    limiter.maxClients = 2;
    long now = 1_000L;
    assertEquals(0, limiter.acquire("first", now));
    assertEquals(0, limiter.acquire("second", now + 1));
    // A known client is still limited while its window is live.
    assertTrue(limiter.acquire("second", now + 2) > 0);
    // The map is full, so a live window gives way to a new visitor instead of locking it out.
    assertEquals(0, limiter.acquire("third", now + 3));
    assertTrue(limiter.acquire("third", now + 4) > 0);
  }
}
