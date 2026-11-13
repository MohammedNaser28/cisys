/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replaces the system resolver in tests so no test depends on DNS. Unless a host is overridden it
 * resolves to a public address, which is what the real github.com and gitlab.com do.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class TestHostResolver implements HostResolver {
  private static final Map<String, String[]> ADDRESSES = new ConcurrentHashMap<>();

  /** Forces a host to the given literal addresses until it is reset. */
  public static void set(String host, String... addresses) {
    if (addresses.length == 0) ADDRESSES.remove(host);
    else ADDRESSES.put(host, addresses.clone());
  }

  public static void reset() {
    ADDRESSES.clear();
  }

  @Override
  public InetAddress[] resolve(String host) throws UnknownHostException {
    String[] values = ADDRESSES.getOrDefault(host, new String[] {"140.82.112.3"});
    InetAddress[] result = new InetAddress[values.length];
    for (int i = 0; i < values.length; i++) result[i] = InetAddress.getByName(values[i]);
    return result;
  }
}
