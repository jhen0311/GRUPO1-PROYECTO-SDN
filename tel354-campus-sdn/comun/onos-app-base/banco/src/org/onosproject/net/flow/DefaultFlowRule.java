// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.flow;

import org.onosproject.net.DeviceId;

/** Regla y, a la vez, entrada del almacén (con estado). El appId sale de los 16 bits altos de la cookie. */
public class DefaultFlowRule implements FlowEntry {
    private DeviceId d;
    private int tabla;
    private int prioridad;
    private TrafficSelector sel;
    private TrafficTreatment trat;
    private long cookie;
    public volatile FlowEntryState estado = FlowEntryState.PENDING_ADD;

    public static FlowRule.Builder builder() {
        DefaultFlowRule r = new DefaultFlowRule();
        return new FlowRule.Builder() {
            public Builder forDevice(DeviceId x) { r.d = x; return this; }
            public Builder forTable(int t) { r.tabla = t; return this; }
            public Builder withPriority(int p) { r.prioridad = p; return this; }
            public Builder withSelector(TrafficSelector s) { r.sel = s; return this; }
            public Builder withTreatment(TrafficTreatment t) { r.trat = t; return this; }
            public Builder withCookie(long c) { r.cookie = c; return this; }
            public Builder makePermanent() { return this; }
            public FlowRule build() { return r; }
        };
    }

    public FlowId id() { return new FlowId(cookie); }
    public short appId() { return (short) (cookie >>> 48); }
    public int priority() { return prioridad; }
    public int tableId() { return tabla; }
    public DeviceId deviceId() { return d; }
    public TrafficSelector selector() { return sel; }
    public TrafficTreatment treatment() { return trat; }
    public FlowEntryState state() { return estado; }
    @Override public String toString() { return String.format("T%d %3d %s -> %s", tabla, prioridad, sel, trat); }
}
