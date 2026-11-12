// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net;

public class ConnectPoint {
    private final DeviceId d;
    private final PortNumber p;
    public ConnectPoint(DeviceId d, PortNumber p) { this.d = d; this.p = p; }
    public DeviceId deviceId() { return d; }
    public PortNumber port() { return p; }
}
