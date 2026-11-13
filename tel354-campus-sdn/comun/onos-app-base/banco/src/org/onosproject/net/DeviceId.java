// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net;

public final class DeviceId {
    private final String v;
    private DeviceId(String v) { this.v = v; }
    public static DeviceId deviceId(String s) { return new DeviceId(s); }
    @Override public String toString() { return v; }
    @Override public boolean equals(Object o) { return o instanceof DeviceId && ((DeviceId) o).v.equals(v); }
    @Override public int hashCode() { return v.hashCode(); }
}
