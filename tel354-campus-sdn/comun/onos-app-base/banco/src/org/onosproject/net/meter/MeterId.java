// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.meter;

public final class MeterId implements MeterCellId {
    private final long v;
    private MeterId(long v) { this.v = v; }
    public static MeterId meterId(long v) { return new MeterId(v); }
    public long id() { return v; }
    @Override public String toString() { return String.valueOf(v); }
    @Override public boolean equals(Object o) { return o instanceof MeterId && ((MeterId) o).v == v; }
    @Override public int hashCode() { return Long.hashCode(v); }
}
