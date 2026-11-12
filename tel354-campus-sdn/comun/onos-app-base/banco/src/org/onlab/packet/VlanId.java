// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onlab.packet;

public class VlanId {
    public static final VlanId ANY = new VlanId((short) 4095);
    private final short v;
    private VlanId(short v) { this.v = v; }
    public static VlanId vlanId(short s) { return new VlanId(s); }
    @Override public String toString() { return this == ANY ? "cualquiera" : String.valueOf(v); }
    @Override public boolean equals(Object o) { return o instanceof VlanId && ((VlanId) o).v == v; }
    @Override public int hashCode() { return v; }
}
