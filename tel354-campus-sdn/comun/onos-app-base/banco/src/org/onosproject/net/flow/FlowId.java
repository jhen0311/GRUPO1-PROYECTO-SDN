// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.flow;

public class FlowId {
    private final long v;
    public FlowId(long v) { this.v = v; }
    public long value() { return v; }
}
