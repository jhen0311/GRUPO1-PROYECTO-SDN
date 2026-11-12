// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onlab.packet;

public class MacAddress {
    private final String v;
    private MacAddress(String v) { this.v = v.toLowerCase(); }
    public static MacAddress valueOf(String s) { return new MacAddress(s); }
    @Override public String toString() { return v; }
    @Override public boolean equals(Object o) { return o instanceof MacAddress && ((MacAddress) o).v.equals(v); }
    @Override public int hashCode() { return v.hashCode(); }
}
