// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.meter;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.DeviceId;

public interface Meter {
    enum Unit { KB_PER_SEC, PKTS_PER_SEC }
    MeterId id();
    MeterState state();
    ApplicationId appId();
    DeviceId deviceId();
}
