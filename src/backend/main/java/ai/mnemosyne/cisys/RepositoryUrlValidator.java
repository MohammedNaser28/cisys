/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The configured repository rules: which hosts may be fetched and which private networks an
 * administrator allowed on purpose. The check runs at project creation, at build start and again
 * immediately before the fetch, because DNS can change after a project was saved.
 */
@ApplicationScoped
public class RepositoryUrlValidator {
  private final HostResolver resolver;
  private final Set<String> allowedHosts;
  private final List<RepositoryUrls.Cidr> allowedNetworks;

  @Inject
  public RepositoryUrlValidator(
      HostResolver resolver,
      @ConfigProperty(name = "cisys.git.allowed-hosts", defaultValue = "github.com,gitlab.com")
          String allowedHosts,
      @ConfigProperty(name = "cisys.git.allowed-networks") Optional<String> allowedNetworks) {
    this.resolver = resolver;
    this.allowedHosts = RepositoryUrls.parseHosts(allowedHosts);
    this.allowedNetworks = RepositoryUrls.parseNetworks(allowedNetworks.orElse(""));
  }

  /** Throws {@link IllegalArgumentException} with a message safe to show to the caller. */
  public void validate(String repositoryUrl) {
    RepositoryUrls.validate(repositoryUrl, allowedHosts, allowedNetworks, resolver);
  }
}
