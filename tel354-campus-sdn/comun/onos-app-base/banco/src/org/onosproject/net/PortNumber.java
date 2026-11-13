// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net;

public final class PortNumber {
    public static final PortNumber CONTROLLER = new PortNumber(-3);
    public static final PortNumber LOCAL = new PortNumber(-2);
    private final long n;
    private PortNumber(long n) { this.n = n; }
    public static PortNumber portNumber(long p) { return new PortNumber(p); }
    public long toLong() { return n; }
    public boolean isLogical() { return n < 0; }
    @Override public String toString() { return n == -3 ? "CONTROLLER" : n == -2 ? "LOCAL" : String.valueOf(n); }
    @Override public boolean equals(Object o) { return o instanceof PortNumber && ((PortNumber) o).n == n; }
    @Override public int hashCode() { return Long.hashCode(n); }
}
