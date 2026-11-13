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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RepositoryUrlValidatorTest {
  private static final String REPOSITORY = "https://github.com/octocat/Hello-World.git";
  private static final Set<String> HOSTS = RepositoryUrls.parseHosts("github.com,gitlab.com");

  private static HostResolver resolved(String... addresses) {
    return host -> {
      InetAddress[] result = new InetAddress[addresses.length];
      for (int i = 0; i < addresses.length; i++) result[i] = InetAddress.getByName(addresses[i]);
      return result;
    };
  }

  private static HostResolver unknown() {
    return host -> {
      throw new UnknownHostException(host);
    };
  }

  private static IllegalArgumentException rejected(
      String url, List<RepositoryUrls.Cidr> networks, HostResolver resolver) {
    return assertThrows(
        IllegalArgumentException.class,
        () -> RepositoryUrls.validate(url, HOSTS, networks, resolver));
  }

  @Test
  void acceptsPublicRepositories() {
    RepositoryUrls.validate(REPOSITORY, HOSTS, List.of(), resolved("140.82.112.3"));
    RepositoryUrls.validate(
        "https://gitlab.com/group/project.git", HOSTS, List.of(), resolved("172.65.251.78"));
    RepositoryUrls.validate(
        "https://github.com:443/octocat/Hello-World.git",
        HOSTS,
        List.of(),
        resolved("140.82.112.3"));
  }

  @Test
  void rejectsBlockedAddresses() {
    for (String address :
        List.of(
            "127.0.0.1",
            "10.0.0.5",
            "10.255.255.255",
            "172.16.0.1",
            "172.31.255.255",
            "192.168.1.10",
            "169.254.169.254",
            "100.64.0.1",
            "0.0.0.0",
            "224.0.0.1",
            "240.0.0.1",
            "192.0.2.10",
            "198.51.100.10",
            "203.0.113.10",
            "::1",
            "fe80::1",
            "fc00::1",
            "ff02::1",
            "2001:db8::1",
            "::ffff:127.0.0.1",
            "::ffff:10.0.0.1")) {
      assertEquals(
          RepositoryUrls.BLOCKED,
          rejected(REPOSITORY, List.of(), resolved(address)).getMessage(),
          address);
    }
  }

  @Test
  void rejectsWhenAnyAddressIsBlocked() {
    assertEquals(
        RepositoryUrls.BLOCKED,
        rejected(REPOSITORY, List.of(), resolved("140.82.112.3", "127.0.0.1")).getMessage());
  }

  @Test
  void rejectsUnknownHosts() {
    assertEquals(RepositoryUrls.UNKNOWN, rejected(REPOSITORY, List.of(), unknown()).getMessage());
    assertEquals(
        RepositoryUrls.UNKNOWN,
        rejected(REPOSITORY, List.of(), host -> new InetAddress[0]).getMessage());
  }

  @Test
  void rejectsHostsThatAreNotAllowed() {
    HostResolver mustNotRun =
        host -> {
          throw new AssertionError("resolved " + host);
        };
    IllegalArgumentException e =
        rejected("https://example.com/octocat/Hello-World.git", List.of(), mustNotRun);
    assertEquals(RepositoryUrls.hostMessage(HOSTS), e.getMessage());
    assertTrue(e.getMessage().contains("github.com"));
    assertTrue(e.getMessage().contains("gitlab.com"));
  }

  @Test
  void rejectsMalformedUrls() {
    for (String url :
        List.of(
            "http://github.com/octocat/Hello-World.git",
            "https://user:token@github.com/octocat/Hello-World.git",
            "https://github.com/octocat/Hello-World.git?ref=main",
            "https://github.com/octocat/Hello-World.git#main",
            "https://github.com:8443/octocat/Hello-World.git",
            "https://github.com",
            "https://github.com/",
            "not a url")) {
      assertEquals(
          RepositoryUrls.SHAPE,
          rejected(url, List.of(), resolved("140.82.112.3")).getMessage(),
          url);
    }
  }

  @Test
  void rejectsMissingAndOverlongUrls() {
    for (String url : new String[] {null, "", "   ", "https://github.com/" + "a".repeat(2048)}) {
      assertEquals(
          RepositoryUrls.REQUIRED, rejected(url, List.of(), resolved("140.82.112.3")).getMessage());
    }
  }

  @Test
  void allowedNetworksOverrideTheBlock() {
    List<RepositoryUrls.Cidr> networks = RepositoryUrls.parseNetworks("10.0.0.0/8,2001:db8::/32");
    RepositoryUrls.validate(REPOSITORY, HOSTS, networks, resolved("10.1.2.3"));
    RepositoryUrls.validate(REPOSITORY, HOSTS, networks, resolved("2001:db8::5"));
    assertEquals(
        RepositoryUrls.BLOCKED,
        rejected(REPOSITORY, RepositoryUrls.parseNetworks("10.0.0.0/16"), resolved("10.1.2.3"))
            .getMessage());
    assertEquals(
        RepositoryUrls.BLOCKED,
        rejected(
                REPOSITORY,
                RepositoryUrls.parseNetworks("2001:db8::/64"),
                resolved("2001:db8:1::5"))
            .getMessage());
  }

  @Test
  void parsesHostListsAndNetworks() {
    assertEquals(
        List.of("github.com", "gitlab.com"),
        List.copyOf(RepositoryUrls.parseHosts(" GitHub.com , gitlab.com ")));
    assertTrue(RepositoryUrls.parseHosts("").isEmpty());
    assertEquals(
        "Use a repository on an allowed host (github.com)",
        RepositoryUrls.hostMessage(RepositoryUrls.parseHosts("github.com")));
    for (String value : new String[] {"10.0.0.0", "10.0.0.0/33", "x/8", "10.0.0.0/"}) {
      assertThrows(IllegalArgumentException.class, () -> RepositoryUrls.Cidr.parse(value), value);
    }
  }

  @Test
  void unwrapsIpv4MappedAddresses() {
    assertEquals(
        4,
        RepositoryUrls.unwrap(
                new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 10, 0, 0, 1})
            .length);
    byte[] ipv6 = new byte[] {0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
    assertEquals(16, RepositoryUrls.unwrap(ipv6).length);
  }
}
