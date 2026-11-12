// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onlab.packet;

public class IpAddress {
    protected String v;
    @Override public String toString() { return v; }
    @Override public boolean equals(Object o) { return o instanceof IpAddress && ((IpAddress) o).v.equals(v); }
    @Override public int hashCode() { return v.hashCode(); }
}
