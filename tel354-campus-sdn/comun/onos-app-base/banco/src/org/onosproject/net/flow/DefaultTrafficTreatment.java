// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.flow;

import org.onlab.packet.MacAddress;
import org.onlab.packet.VlanId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flow.instructions.Instructions;
import org.onosproject.net.meter.MeterId;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Como en ONOS 2.7.0, equals compara las acciones y la tabla de salto, pero NO el meter
 * (DefaultTrafficTreatment.equals: immediate, deferred, table y meta).
 */
public final class DefaultTrafficTreatment implements TrafficTreatment {
    private final List<Instruction> acciones = new ArrayList<>();
    private final Set<Instructions.MeterInstruction> meter = new HashSet<>();
    private Integer tabla;

    public static TrafficTreatment.Builder builder() {
        DefaultTrafficTreatment t = new DefaultTrafficTreatment();
        return new TrafficTreatment.Builder() {
            public Builder meter(MeterId m) { t.meter.add(new Instructions.MeterInstruction(m)); return this; }
            public Builder pushVlan() { t.acciones.add(new Instructions.Otra("push_vlan")); return this; }
            public Builder setVlanId(VlanId v) { t.acciones.add(new Instructions.Otra("vlan:=" + v)); return this; }
            public Builder popVlan() { t.acciones.add(new Instructions.Otra("pop_vlan")); return this; }
            public Builder setEthDst(MacAddress m) { t.acciones.add(new Instructions.Otra("eth_dst:=" + m)); return this; }
            public Builder setOutput(PortNumber p) { t.acciones.add(new Instructions.OutputInstruction(p)); return this; }
            public Builder transition(Integer tb) { t.tabla = tb; return this; }
            public TrafficTreatment build() { return t; }
        };
    }

    public List<Instruction> allInstructions() {
        List<Instruction> l = new ArrayList<>(acciones);
        l.addAll(meter);
        return l;
    }

    public Instructions.MeterInstruction metered() { return meter.isEmpty() ? null : meter.iterator().next(); }
    public Set<Instructions.MeterInstruction> meters() { return meter; }

    @Override public boolean equals(Object o) {
        return o instanceof DefaultTrafficTreatment && ((DefaultTrafficTreatment) o).acciones.equals(acciones)
                && Objects.equals(((DefaultTrafficTreatment) o).tabla, tabla);
    }
    @Override public int hashCode() { return Objects.hash(acciones, tabla); }
    @Override public String toString() { return (meter.isEmpty() ? "" : meter + " ") + acciones + (tabla == null ? "" : " goto:" + tabla); }
}
