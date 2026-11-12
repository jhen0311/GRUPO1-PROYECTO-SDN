// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.meter;

import org.onosproject.net.DeviceId;

import java.util.Collection;

public interface MeterService {
    Meter submit(MeterRequest r);
    void withdraw(MeterRequest r, MeterCellId id);
    Collection<Meter> getMeters(DeviceId d);
    Collection<Meter> getAllMeters();
}
