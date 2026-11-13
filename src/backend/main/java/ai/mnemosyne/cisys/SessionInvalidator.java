/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Signs every user out of the cisys realm when the application shuts down. The sessions live in
 * Keycloak, which keeps running after cisys stops, so they would otherwise survive a restart.
 */
@ApplicationScoped
public class SessionInvalidator {
  private static final Logger LOG = Logger.getLogger(SessionInvalidator.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final Pattern ACCESS_TOKEN =
      Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");

  @ConfigProperty(name = "cisys.session.invalidate-on-shutdown", defaultValue = "true")
  boolean enabled;

  @ConfigProperty(name = "cisys.keycloak.url")
  String keycloakUrl;

  @ConfigProperty(name = "cisys.keycloak.realm", defaultValue = "cisys")
  String realm;

  @ConfigProperty(name = "cisys.keycloak.admin-client-id")
  String clientId;

  @ConfigProperty(name = "cisys.keycloak.admin-client-secret")
  String clientSecret;

  void onShutdown(@Observes ShutdownEvent event) {
    if (enabled && !isLiveReload(LaunchMode.current(), event.isStandardShutdown())) invalidate();
  }

  /**
   * In development mode a live reload also fires ShutdownEvent, as a standard shutdown; really
   * stopping is not standard.
   */
  static boolean isLiveReload(LaunchMode mode, boolean standardShutdown) {
    return mode == LaunchMode.DEVELOPMENT && standardShutdown;
  }

  /** Never throws: a Keycloak problem must not get in the way of shutting down. */
  boolean invalidate() {
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
      String form =
          "grant_type=client_credentials&client_id="
              + encode(clientId)
              + "&client_secret="
              + encode(clientSecret);
      HttpResponse<String> token =
          client.send(
              request("/realms/" + realm + "/protocol/openid-connect/token")
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .POST(HttpRequest.BodyPublishers.ofString(form))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      Matcher accessToken = ACCESS_TOKEN.matcher(token.body());
      if (token.statusCode() != 200 || !accessToken.find()) {
        LOG.warnf(
            "Sessions were not removed: Keycloak refused client '%s' (HTTP %d). It is defined in contrib/keycloak/realm.json, which is only imported into a new realm.",
            clientId, token.statusCode());
        return false;
      }
      HttpResponse<Void> logout =
          client.send(
              request("/admin/realms/" + realm + "/logout-all")
                  .header("Authorization", "Bearer " + accessToken.group(1))
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      if (logout.statusCode() / 100 != 2) {
        LOG.warnf("Sessions were not removed: Keycloak answered HTTP %d.", logout.statusCode());
        return false;
      }
      LOG.info("Removed all user sessions");
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("Sessions were not removed: interrupted.");
    } catch (IOException | IllegalArgumentException e) {
      LOG.warnf("Sessions were not removed: %s", e.toString());
    }
    return false;
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create(keycloakUrl + path)).timeout(TIMEOUT);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
