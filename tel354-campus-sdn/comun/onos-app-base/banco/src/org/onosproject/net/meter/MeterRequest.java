// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.meter;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.DeviceId;

import java.util.Collection;

public interface MeterRequest {
    DeviceId deviceId();
    ApplicationId appId();
    Meter.Unit unit();
    Collection<Band> bands();

    interface Builder {
        Builder forDevice(DeviceId d);
        Builder fromApp(ApplicationId a);
        Builder withUnit(Meter.Unit u);
        Builder burst();
        Builder withBands(Collection<Band> b);
        MeterRequest add();
        MeterRequest remove();
    }
}
