// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.link;

import org.onosproject.net.DeviceId;
import org.onosproject.net.Link;

import java.util.Set;

public interface LinkService {
    Set<Link> getDeviceEgressLinks(DeviceId d);
    Set<Link> getDeviceLinks(DeviceId d);
    void addListener(LinkListener l);
    void removeListener(LinkListener l);
}
