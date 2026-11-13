/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Resolves a host name to every address it has. Replaced in tests so they never use DNS. */
public interface HostResolver {
  InetAddress[] resolve(String host) throws UnknownHostException;
}
