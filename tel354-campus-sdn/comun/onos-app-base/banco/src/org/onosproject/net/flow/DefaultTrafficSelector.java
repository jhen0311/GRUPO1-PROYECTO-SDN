// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.flow;

import org.onlab.packet.IpPrefix;
import org.onlab.packet.VlanId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.PortCriterion;

import java.util.TreeMap;

/** Selector como mapa de criterios; igualdad por contenido, como en ONOS. */
public final class DefaultTrafficSelector implements TrafficSelector {
    private final TreeMap<Criterion.Type, Object> c = new TreeMap<>();

    public static TrafficSelector.Builder builder() {
        DefaultTrafficSelector s = new DefaultTrafficSelector();
        return new TrafficSelector.Builder() {
            public Builder matchInPort(PortNumber p) { s.c.put(Criterion.Type.IN_PORT, p); return this; }
            public Builder matchVlanId(VlanId v) { s.c.put(Criterion.Type.VLAN_VID, v); return this; }
            public Builder matchEthType(short t) { s.c.put(Criterion.Type.ETH_TYPE, String.format("0x%04x", t & 0xffff)); return this; }
            public Builder matchIPDst(IpPrefix p) { s.c.put(Criterion.Type.IPV4_DST, p); return this; }
            public TrafficSelector build() { return s; }
        };
    }

    public Criterion getCriterion(Criterion.Type t) {
        return t == Criterion.Type.IN_PORT && c.containsKey(t) ? new PortCriterion((PortNumber) c.get(t)) : null;
    }

    @Override public boolean equals(Object o) { return o instanceof DefaultTrafficSelector && ((DefaultTrafficSelector) o).c.equals(c); }
    @Override public int hashCode() { return c.hashCode(); }
    @Override public String toString() { return c.toString(); }
}
