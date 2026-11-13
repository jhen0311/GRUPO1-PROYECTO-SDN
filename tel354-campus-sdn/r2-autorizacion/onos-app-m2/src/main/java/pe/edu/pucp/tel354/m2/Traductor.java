package pe.edu.pucp.tel354.m2;

import org.onlab.packet.Ethernet;
import org.onlab.packet.Ip4Prefix;
import org.onlab.packet.TpPort;
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
import pe.edu.pucp.tel354.api.Pipeline;
import pe.edu.pucp.tel354.m2.nucleo.Politica;
import pe.edu.pucp.tel354.m2.nucleo.ReglaT1;

/** Traduce las reglas de M2 a FlowRule con la cookie de M2 (LLSD R3, Tabla 7). */
final class Traductor {

    private Traductor() {
    }

    /** Regla permanente de la Tabla 1 (tipos 0x03 y 0x04). */
    static FlowRule tabla1(DeviceId d, ReglaT1 r, ApplicationId app, MeterId meter) {
        TrafficSelector.Builder s = DefaultTrafficSelector.builder();
        if (r.vlan != null) {
            s.matchVlanId(VlanId.vlanId(r.vlan.shortValue()));
        }
        if (r.ipv4) {
            s.matchEthType(Ethernet.TYPE_IPV4);
        }
        if (r.ipOrigen != null) {
            s.matchIPSrc(Ip4Prefix.valueOf(r.ipOrigen));
        }
        if (r.ipDestino != null) {
            s.matchIPDst(Ip4Prefix.valueOf(r.ipDestino));
        }
        if (r.proto != null) {
            l4(s, r.proto, r.puertoDestino == null ? -1 : r.puertoDestino);
        }
        TrafficTreatment.Builder t = DefaultTrafficTreatment.builder();
        if (meter != null) {
            t.meter(meter);
        }
        if (r.controlador) {
            t.setOutput(PortNumber.CONTROLLER);
        }
        if (r.irATabla != null) {
            t.transition(r.irATabla);
        }
        return DefaultFlowRule.builder().forDevice(d).forTable(Pipeline.T_AUTORIZACION)
                .withPriority(r.prioridad).withSelector(s.build()).withTreatment(t.build())
                .withCookie(Cookies.cookie(app, r.tipo, r.id, r.indice)).makePermanent().build();
    }

    /** Coincidencia de la cuádrupla: origen /32, destino /32, protocolo y puerto destino si es TCP o UDP. */
    static TrafficSelector cuadrupla(Ip4Prefix origen, Ip4Prefix destino, int proto, int puerto) {
        TrafficSelector.Builder s = DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4)
                .matchIPSrc(origen).matchIPDst(destino);
        l4(s, proto, puerto);
        return s.build();
    }

    private static void l4(TrafficSelector.Builder s, int proto, int puerto) {
        s.matchIPProtocol((byte) proto);
        if (puerto >= 0 && proto == Politica.TCP) {
            s.matchTcpDst(TpPort.tpPort(puerto));
        } else if (puerto >= 0 && proto == Politica.UDP) {
            s.matchUdpDst(TpPort.tpPort(puerto));
        }
    }

    /**
     * DROP 150 de M2 (LLSD R3, sección 5.3): la cuádrupla, hard_timeout de 8 s y cookie
     * (M2, 0x02, sesion.id, contador).
     */
    static FlowRule drop150(DeviceId d, ApplicationId app, long sesionId, int indice, TrafficSelector sel) {
        return DefaultFlowRule.builder().forDevice(d).forTable(Pipeline.T_AUTORIZACION)
                .withPriority(Pipeline.P_DENEGADO).withSelector(sel)
                .withTreatment(DefaultTrafficTreatment.builder().build())      // sin acciones = drop
                .withCookie(Cookies.cookie(app, Cookies.SESION, sesionId, indice))
                .withHardTimeout(Pipeline.HARD_DENEGADO_SEG).build();
    }

    /**
     * Permiso 110 de carrera: la inscripción cambió después de que M1 activó la sesión. Igual que
     * la 110 de M1 (goto Tabla 2), pero con la cookie de M2, así que se retira con la sesión.
     */
    static FlowRule curso110(DeviceId d, ApplicationId app, long sesionId, int indice, TrafficSelector sel) {
        return DefaultFlowRule.builder().forDevice(d).forTable(Pipeline.T_AUTORIZACION)
                .withPriority(Pipeline.P_CURSO).withSelector(sel)
                .withTreatment(DefaultTrafficTreatment.builder().transition(Pipeline.T_ENTREGA).build())
                .withCookie(Cookies.cookie(app, Cookies.SESION, sesionId, indice)).makePermanent().build();
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
