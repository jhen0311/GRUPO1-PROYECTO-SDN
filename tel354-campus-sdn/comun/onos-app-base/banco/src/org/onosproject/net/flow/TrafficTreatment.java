// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.flow;

import org.onlab.packet.MacAddress;
import org.onlab.packet.VlanId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flow.instructions.Instructions;
import org.onosproject.net.meter.MeterId;

import java.util.List;
import java.util.Set;

public interface TrafficTreatment {
    List<Instruction> allInstructions();
    Instructions.MeterInstruction metered();
    Set<Instructions.MeterInstruction> meters();

    interface Builder {
        Builder meter(MeterId m);
        Builder pushVlan();
        Builder setVlanId(VlanId v);
        Builder popVlan();
        Builder setEthDst(MacAddress m);
        Builder setOutput(PortNumber p);
        Builder transition(Integer tabla);
        TrafficTreatment build();
    }
}
