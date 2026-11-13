// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.device;

import org.onosproject.net.Device;
import org.onosproject.net.Port;

public class DeviceEvent implements org.onosproject.event.Event<DeviceEvent.Type, Device> {
    public enum Type { DEVICE_ADDED, DEVICE_UPDATED, DEVICE_REMOVED, DEVICE_SUSPENDED, DEVICE_AVAILABILITY_CHANGED,
        PORT_ADDED, PORT_UPDATED, PORT_REMOVED, PORT_STATS_UPDATED }
    private final Type t;
    private final Device d;
    private final Port p;
    private final long ms = System.currentTimeMillis();
    public DeviceEvent(Type t, Device d, Port p) { this.t = t; this.d = d; this.p = p; }
    public Type type() { return t; }
    public Device subject() { return d; }
    public long time() { return ms; }
    public Port port() { return p; }
}
