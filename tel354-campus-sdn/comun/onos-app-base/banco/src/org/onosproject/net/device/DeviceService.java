// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.device;

import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Port;

import java.util.List;

public interface DeviceService {
    boolean isAvailable(DeviceId d);
    Iterable<Device> getAvailableDevices();
    List<Port> getPorts(DeviceId d);
    void addListener(DeviceListener l);
    void removeListener(DeviceListener l);
}
