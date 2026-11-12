// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.mastership;

public interface MastershipService {
    boolean isLocalMaster(org.onosproject.net.DeviceId d);
}
