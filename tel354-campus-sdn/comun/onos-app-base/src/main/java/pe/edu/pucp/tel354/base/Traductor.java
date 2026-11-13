package pe.edu.pucp.tel354.base;

import org.onlab.packet.Ip4Prefix;
import org.onlab.packet.MacAddress;
import org.onlab.packet.VlanId;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.meter.MeterId;
import pe.edu.pucp.tel354.api.Cookies;
import pe.edu.pucp.tel354.base.nucleo.Regla;

/** Traduce una {@link Regla} a FlowRule permanente con la cookie de su módulo. */
final class Traductor {

    private Traductor() {
    }

    static FlowRule flowRule(DeviceId d, Regla r, ApplicationId app, MeterId meter) {
        TrafficSelector.Builder s = DefaultTrafficSelector.builder();
        if (r.puertoEntrada != null) {
            s.matchInPort(PortNumber.portNumber(r.puertoEntrada));
        }
        if (r.vlan != null) {
            s.matchVlanId(r.vlan == Regla.VLAN_PRESENTE ? VlanId.ANY : VlanId.vlanId(r.vlan.shortValue()));
        }
        if (r.ethType != null) {
            s.matchEthType((short) r.ethType.intValue());
        }
        if (r.ipDestino != null) {
            s.matchIPDst(Ip4Prefix.valueOf(r.ipDestino));
        }
        TrafficTreatment.Builder t = DefaultTrafficTreatment.builder();
        if (meter != null) {
            t.meter(meter);
        }
        if (r.pushVlan) {
            t.pushVlan();
        }
        if (r.fijarVlan != null) {
            t.setVlanId(VlanId.vlanId(r.fijarVlan.shortValue()));
        }
        if (r.popVlan) {
            t.popVlan();
        }
        if (r.macDestino != null) {
            t.setEthDst(MacAddress.valueOf(r.macDestino));
        }
        if (r.salida != null) {
            t.setOutput(r.salida == Regla.CONTROLADOR ? PortNumber.CONTROLLER : PortNumber.portNumber(r.salida));
        }
        if (r.irATabla != null) {
            t.transition(r.irATabla);
        }
        int tipo = r.modulo == Regla.Modulo.M7 ? Cookies.BASE : Cookies.CAMINO;
        return DefaultFlowRule.builder()
                .forDevice(d)
                .forTable(r.tabla)
                .withPriority(r.prioridad)
                .withSelector(s.build())
                .withTreatment(t.build())
                .withCookie(Cookies.cookie(app, tipo, r.id, r.indice))
                .makePermanent()
                .build();
    }

    /**
     * Misma regla (la cookie ya coincide): tabla, prioridad, coincidencia, acciones y meter. El meter
     * se compara aparte porque DefaultTrafficTreatment.equals no lo incluye (ONOS 2.7.0).
     */
    static boolean igual(FlowRule a, FlowRule b) {
        return a.tableId() == b.tableId() && a.priority() == b.priority()
                && a.selector().equals(b.selector()) && a.treatment().equals(b.treatment())
                && a.treatment().meters().equals(b.treatment().meters());
    }
}
