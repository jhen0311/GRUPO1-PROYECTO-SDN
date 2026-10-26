package pe.edu.pucp.tel354.m1.onos;

import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.Ip4Prefix;
import org.onlab.packet.MacAddress;
import org.onlab.packet.TpPort;
import org.onlab.packet.VlanId;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceAdminService;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleOperations;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.PacketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.edu.pucp.tel354.m1.nucleo.Plataforma;
import pe.edu.pucp.tel354.m1.nucleo.Sesion;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementación de la Plataforma de M1 con los servicios nativos de ONOS 1.13:
 * PacketService (Packet-Out), FlowRuleService (reglas de sesión) y DeviceAdminService (rebote).
 *
 * Las reglas son las mismas que emulaba sesion_manual.py (AVZ07, Tablas 11 a 13), con
 * hard_timeout igual a lo que le queda a la sesión y cookie del tipo 0x02:
 *   bits 63-48 id de la app (ONOS) | 47-40 tipo 0x02 | 39-8 sesion.id | 7-0 índice de la regla
 */
public class PlataformaOnos implements Plataforma {

    static final long TIPO_SESION = 0x02;

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ApplicationId appId;
    private final PacketService packetService;
    private final FlowRuleService flowRuleService;
    private final DeviceAdminService deviceAdminService;

    public PlataformaOnos(ApplicationId appId, PacketService packetService, FlowRuleService flowRuleService,
                          DeviceAdminService deviceAdminService) {
        this.appId = appId;
        this.packetService = packetService;
        this.flowRuleService = flowRuleService;
        this.deviceAdminService = deviceAdminService;
    }

    /** Cookie de la regla número {@code indice} de la sesión. */
    long cookie(Sesion s, int indice) {
        return (((long) appId.id()) << 48) | (TIPO_SESION << 40) | ((s.id & 0xffffffffL) << 8) | (indice & 0xff);
    }

    /** Sesión a la que pertenece una cookie de M1, o -1. */
    long sesionDeCookie(long cookie) {
        if ((short) (cookie >>> 48) != appId.id() || ((cookie >>> 40) & 0xff) != TIPO_SESION) {
            return -1;
        }
        return (cookie >>> 8) & 0xffffffffL;
    }

    @Override
    public void enviarTrama(String dpid, int puerto, byte[] trama) {
        TrafficTreatment t = DefaultTrafficTreatment.builder().setOutput(PortNumber.portNumber(puerto)).build();
        packetService.emit(new DefaultOutboundPacket(DeviceId.deviceId(dpid), t, ByteBuffer.wrap(trama)));
    }

    private FlowRule regla(Sesion s, int indice, int tabla, int prioridad, TrafficSelector sel,
                           TrafficTreatment trat, int timeout) {
        return DefaultFlowRule.builder()
                .forDevice(DeviceId.deviceId(s.dpid))
                .forTable(tabla)
                .withPriority(prioridad)
                .withSelector(sel)
                .withTreatment(trat)
                .withCookie(cookie(s, indice))
                .withHardTimeout(timeout)
                .build();
    }

    @Override
    public List<Object> instalarAutenticacion(Sesion s, List<Object> retirarAntes) {
        PortNumber p = PortNumber.portNumber(s.puerto);
        MacAddress mac = MacAddress.valueOf(s.mac);
        int t = s.restanteSeg();
        TrafficTreatment alControlador = DefaultTrafficTreatment.builder().setOutput(PortNumber.CONTROLLER).build();

        // 525: DHCP del equipo autenticado -> M1 (relay)
        FlowRule r525 = regla(s, 0, 0, 525, DefaultTrafficSelector.builder()
                .matchInPort(p).matchEthSrc(mac).matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_UDP).matchUdpDst(TpPort.tpPort(67)).build(), alControlador, t);
        // 505: IPv4 del equipo con una IP que no es la de su sesión -> controlador
        FlowRule r505 = regla(s, 1, 0, 505, DefaultTrafficSelector.builder()
                .matchInPort(p).matchEthSrc(mac).matchEthType(Ethernet.TYPE_IPV4).build(), alControlador, t);
        aplicar(retirarAntes, r525, r505);
        List<Object> l = new ArrayList<>();
        l.add(r525);
        l.add(r505);
        return l;
    }

    @Override
    public List<Object> instalarActivacion(Sesion s, List<AccesoCurso> cursos, List<Object> retirarAntes) {
        PortNumber p = PortNumber.portNumber(s.puerto);
        MacAddress mac = MacAddress.valueOf(s.mac);
        VlanId vlan = VlanId.vlanId((short) s.vlan);
        Ip4Prefix ip = Ip4Prefix.valueOf(s.ip + "/32");
        int t = s.restanteSeg();
        List<FlowRule> reglas = new ArrayList<>();

        // 520: binding puerto + MAC + IP -> etiqueta VLAN del pabellón (o 999) y Tabla 1
        reglas.add(regla(s, 2, 0, 520, DefaultTrafficSelector.builder()
                .matchInPort(p).matchEthSrc(mac).matchEthType(Ethernet.TYPE_IPV4).matchIPSrc(ip).build(),
                DefaultTrafficTreatment.builder().pushVlan().setVlanId(vlan).transition(1).build(), t));
        // 200 y 190 (Tabla 2): entrega al host por IP y por MAC, retirando la VLAN
        reglas.add(regla(s, 3, 2, 200, DefaultTrafficSelector.builder()
                .matchVlanId(vlan).matchEthType(Ethernet.TYPE_IPV4).matchIPDst(ip).build(),
                DefaultTrafficTreatment.builder().popVlan().setEthDst(mac).setOutput(p).build(), t));
        reglas.add(regla(s, 4, 2, 190, DefaultTrafficSelector.builder()
                .matchVlanId(vlan).matchEthDst(mac).build(),
                DefaultTrafficTreatment.builder().popVlan().setOutput(p).build(), t));
        // 110 (Tabla 1): un servidor de un curso en el que el usuario está inscrito
        int i = 5;
        for (AccesoCurso c : cursos) {
            TrafficSelector.Builder sel = DefaultTrafficSelector.builder()
                    .matchEthType(Ethernet.TYPE_IPV4).matchIPSrc(ip)
                    .matchIPDst(Ip4Prefix.valueOf(c.ip + "/32"));
            if ("TCP".equals(c.protocolo)) {
                sel.matchIPProtocol(IPv4.PROTOCOL_TCP).matchTcpDst(TpPort.tpPort(c.puerto));
            } else {
                sel.matchIPProtocol(IPv4.PROTOCOL_UDP).matchUdpDst(TpPort.tpPort(c.puerto));
            }
            reglas.add(regla(s, i++, 1, 110, sel.build(),
                             DefaultTrafficTreatment.builder().transition(2).build(), t));
        }
        aplicar(retirarAntes, reglas.toArray(new FlowRule[0]));
        return new ArrayList<Object>(reglas);
    }

    /** Etapa 1: retirar las reglas viejas; etapa 2 (al terminar la 1): instalar las nuevas. */
    private void aplicar(List<Object> retirarAntes, FlowRule... nuevas) {
        FlowRuleOperations.Builder ops = FlowRuleOperations.builder();
        boolean hayViejas = false;
        for (Object o : retirarAntes) {
            if (o instanceof FlowRule) {
                ops.remove((FlowRule) o);
                hayViejas = true;
            }
        }
        if (hayViejas) {
            ops.newStage();
        }
        for (FlowRule f : nuevas) {
            ops.add(f);
        }
        flowRuleService.apply(ops.build());
    }

    @Override
    public void retirar(List<Object> reglas) {
        List<FlowRule> l = new ArrayList<>();
        for (Object o : reglas) {
            if (o instanceof FlowRule) {
                l.add((FlowRule) o);
            }
        }
        if (!l.isEmpty()) {
            flowRuleService.removeFlowRules(l.toArray(new FlowRule[0]));
        }
    }

    @Override
    public void rebotarPuerto(String dpid, int puerto) {
        Thread t = new Thread(() -> {
            try {
                DeviceId d = DeviceId.deviceId(dpid);
                PortNumber p = PortNumber.portNumber(puerto);
                deviceAdminService.changePortState(d, p, false);
                Thread.sleep(1500);
                deviceAdminService.changePortState(d, p, true);
                log.info("M1: puerto {}/{} rebotado para forzar la reautenticación", dpid, puerto);
            } catch (Exception e) {
                log.warn("M1: no se pudo rebotar {}/{}: {}", dpid, puerto, e.toString());
            }
        }, "m1-rebote");
        t.setDaemon(true);
        t.start();
    }
}
