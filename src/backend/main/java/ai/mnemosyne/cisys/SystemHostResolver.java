/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.enterprise.context.ApplicationScoped;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** The default resolver: the JVM's own name lookup. */
@ApplicationScoped
public class SystemHostResolver implements HostResolver {
  @Override
  public InetAddress[] resolve(String host) throws UnknownHostException {
    return InetAddress.getAllByName(host);
  }
}
