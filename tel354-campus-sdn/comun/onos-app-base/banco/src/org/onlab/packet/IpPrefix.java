// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onlab.packet;

public class IpPrefix {
    protected String v;
    public boolean contains(IpAddress a) { return false; }
    @Override public String toString() { return v; }
    @Override public boolean equals(Object o) { return o instanceof IpPrefix && ((IpPrefix) o).v.equals(v); }
    @Override public int hashCode() { return v.hashCode(); }
}
