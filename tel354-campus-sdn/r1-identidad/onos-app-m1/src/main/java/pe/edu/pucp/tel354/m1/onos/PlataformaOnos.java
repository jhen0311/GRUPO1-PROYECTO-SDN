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
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleOperations;
import org.onosproject.net.flow.FlowRuleOperationsContext;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.PortCriterion;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flow.instructions.Instructions;
import org.onosproject.net.meter.MeterId;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.PacketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.edu.pucp.tel354.api.Cookies;
import pe.edu.pucp.tel354.api.Pipeline;
import pe.edu.pucp.tel354.m1.nucleo.Plataforma;
import pe.edu.pucp.tel354.m1.nucleo.ReglaRecuperada;
import pe.edu.pucp.tel354.m1.nucleo.Sesion;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

/**
 * Plataforma de M1 con los servicios nativos de ONOS 2.7: PacketService (Packet-Out),
 * FlowRuleService (reglas de sesión y lectura del meter ctl_P de la regla 800) y
 * DeviceAdminService (rebote del puerto).
 *
 * Reglas de AVZ07 (Tablas 11 a 13), con hard_timeout igual a lo que le queda a la sesión y
 * cookie (M1, 0x02, sesion.id, índice).
 */
public class PlataformaOnos implements Plataforma {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ApplicationId appId;
    private final PacketService packetService;
    private final FlowRuleService flowRuleService;
    private final DeviceAdminService deviceAdminService;
    private final ScheduledExecutorService temporizador;
    private volatile LongConsumer alFallar = id -> { };

    public PlataformaOnos(ApplicationId appId, PacketService packetService, FlowRuleService flowRuleService,
                          DeviceAdminService deviceAdminService, ScheduledExecutorService temporizador) {
        this.appId = appId;
        this.packetService = packetService;
        this.flowRuleService = flowRuleService;
        this.deviceAdminService = deviceAdminService;
        this.temporizador = temporizador;
    }

    /** Cookie de la regla número {@code indice} de la sesión. */
    long cookie(Sesion s, int indice) {
        return Cookies.cookie(appId, Cookies.SESION, s.id, indice);
    }

    /** Sesión a la que pertenece una cookie de M1, o -1. */
    long sesionDeCookie(long cookie) {
        return Cookies.es(cookie, appId, Cookies.SESION) ? Cookies.id(cookie) : -1;
    }

    @Override
    public void alFallarInstalacion(LongConsumer f) {
        alFallar = f;
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

    /**
     * Meter ctl_P del puerto: el de la regla 800 (ARP) del mismo puerto, que instala el régimen
     * base (M7; en el piloto, base_slice.py). Así 800, 525 y 505 comparten 10 pps por puerto.
     */
    MeterId meterDelPuerto(DeviceId d, PortNumber p) {
        for (FlowEntry e : flowRuleService.getFlowEntries(d)) {
            if (e.tableId() != Pipeline.T_ADMISION || e.priority() != Pipeline.P_ARP) {
                continue;
            }
            Criterion c = e.selector().getCriterion(Criterion.Type.IN_PORT);
            if (!(c instanceof PortCriterion) || !((PortCriterion) c).port().equals(p)) {
                continue;
            }
            Instructions.MeterInstruction mi = e.treatment().metered();
            if (mi != null) {
                return mi.meterId();
            }
            for (Instruction i : e.treatment().allInstructions()) {
                if (i instanceof Instructions.MeterInstruction) {
                    return ((Instructions.MeterInstruction) i).meterId();
                }
            }
        }
        return null;
    }

    @Override
    public List<Object> instalarAutenticacion(Sesion s, List<Object> retirarAntes) {
        DeviceId d = DeviceId.deviceId(s.dpid);
        PortNumber p = PortNumber.portNumber(s.puerto);
        MacAddress mac = MacAddress.valueOf(s.mac);
        int t = s.restanteSeg();
        MeterId ctl = meterDelPuerto(d, p);
        if (ctl == null) {
            log.warn("M1: {}/{} sin meter ctl_P en su regla 800: 525 y 505 quedan sin límite", s.dpid, s.puerto);
        }
        TrafficTreatment.Builder tb = DefaultTrafficTreatment.builder();
        if (ctl != null) {
            tb.meter(ctl);
        }
        TrafficTreatment alControlador = tb.setOutput(PortNumber.CONTROLLER).build();

        // 525: DHCP del equipo autenticado -> M1 (relay)
        FlowRule r525 = regla(s, Cookies.IDX_525, Pipeline.T_ADMISION, Pipeline.P_DHCP, DefaultTrafficSelector.builder()
                .matchInPort(p).matchEthSrc(mac).matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_UDP).matchUdpDst(TpPort.tpPort(67)).build(), alControlador, t);
        // 505: IPv4 del equipo con una IP que no es la de su sesión -> controlador (P2)
        FlowRule r505 = regla(s, Cookies.IDX_505, Pipeline.T_ADMISION, Pipeline.P_OTRA_IP, DefaultTrafficSelector.builder()
                .matchInPort(p).matchEthSrc(mac).matchEthType(Ethernet.TYPE_IPV4).build(), alControlador, t);
        aplicar(s.id, retirarAntes, r525, r505);
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
        reglas.add(regla(s, Cookies.IDX_520, Pipeline.T_ADMISION, Pipeline.P_BINDING, DefaultTrafficSelector.builder()
                .matchInPort(p).matchEthSrc(mac).matchEthType(Ethernet.TYPE_IPV4).matchIPSrc(ip).build(),
                DefaultTrafficTreatment.builder().pushVlan().setVlanId(vlan).transition(Pipeline.T_AUTORIZACION)
                        .build(), t));
        // 200 y 190 (Tabla 2): entrega al host por IP y por MAC, retirando la VLAN
        reglas.add(regla(s, Cookies.IDX_200, Pipeline.T_ENTREGA, Pipeline.P_HOST_IP, DefaultTrafficSelector.builder()
                .matchVlanId(vlan).matchEthType(Ethernet.TYPE_IPV4).matchIPDst(ip).build(),
                DefaultTrafficTreatment.builder().popVlan().setEthDst(mac).setOutput(p).build(), t));
        reglas.add(regla(s, Cookies.IDX_190, Pipeline.T_ENTREGA, Pipeline.P_HOST_MAC, DefaultTrafficSelector.builder()
                .matchVlanId(vlan).matchEthDst(mac).build(),
                DefaultTrafficTreatment.builder().popVlan().setOutput(p).build(), t));
        // 110 (Tabla 1): un servidor de un curso en el que el usuario está inscrito
        int i = Cookies.IDX_110;
        for (AccesoCurso c : cursos) {
            TrafficSelector.Builder sel = DefaultTrafficSelector.builder()
                    .matchEthType(Ethernet.TYPE_IPV4).matchIPSrc(ip)
                    .matchIPDst(Ip4Prefix.valueOf(c.ip + "/32"));
            if ("TCP".equals(c.protocolo)) {
                sel.matchIPProtocol(IPv4.PROTOCOL_TCP).matchTcpDst(TpPort.tpPort(c.puerto));
            } else {
                sel.matchIPProtocol(IPv4.PROTOCOL_UDP).matchUdpDst(TpPort.tpPort(c.puerto));
            }
            reglas.add(regla(s, i++, Pipeline.T_AUTORIZACION, Pipeline.P_CURSO, sel.build(),
                             DefaultTrafficTreatment.builder().transition(Pipeline.T_ENTREGA).build(), t));
        }
        aplicar(s.id, retirarAntes, reglas.toArray(new FlowRule[0]));
        return new ArrayList<Object>(reglas);
    }

    /**
     * Etapa 1: retirar las reglas viejas; etapa 2 (al terminar la 1): instalar las nuevas.
     * Si el switch rechaza la operación (p. ej., tabla llena), la sesión se cierra con TABLA_LLENA.
     */
    private void aplicar(long sesionId, List<Object> retirarAntes, FlowRule... nuevas) {
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
        flowRuleService.apply(ops.build(new FlowRuleOperationsContext() {
            @Override
            public void onError(FlowRuleOperations fallidas) {
                log.warn("M1: el switch rechazó reglas de la sesión {}: {}", sesionId, fallidas);
                alFallar.accept(sesionId);
            }
        }));
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
    public void rebotarPuerto(String dpid, int puerto, int segundosAbajo, Runnable alSubir) {
        DeviceId d = DeviceId.deviceId(dpid);
        PortNumber p = PortNumber.portNumber(puerto);
        try {
            deviceAdminService.changePortState(d, p, false);
        } catch (Exception e) {
            log.warn("M1: no se pudo bajar {}/{}: {}", dpid, puerto, e.toString());
            return;
        }
        temporizador.schedule(() -> {
            try {
                deviceAdminService.changePortState(d, p, true);
                log.info("M1: puerto {}/{} rebotado ({} s abajo) para forzar la reautenticación",
                         dpid, puerto, segundosAbajo);
                alSubir.run();
            } catch (Exception e) {
                log.warn("M1: no se pudo subir {}/{}: {}", dpid, puerto, e.toString());
            }
        }, segundosAbajo, TimeUnit.SECONDS);
    }

    @Override
    public void rebotarAhora(List<String> puertos, int segundosAbajo) {
        List<DeviceId> ds = new ArrayList<>();
        List<PortNumber> ps = new ArrayList<>();
        for (String dp : puertos) {
            int i = dp.lastIndexOf('/');
            ds.add(DeviceId.deviceId(dp.substring(0, i)));
            ps.add(PortNumber.portNumber(Long.parseLong(dp.substring(i + 1))));
        }
        for (int k = 0; k < ds.size(); k++) {
            deviceAdminService.changePortState(ds.get(k), ps.get(k), false);
        }
        try {
            Thread.sleep(segundosAbajo * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (int k = 0; k < ds.size(); k++) {
            deviceAdminService.changePortState(ds.get(k), ps.get(k), true);
        }
        log.info("M1: {} puertos rebotados al desactivar la app", ds.size());
    }

    @Override
    public Map<Long, List<ReglaRecuperada>> recuperar(String dpid) {
        Map<Long, List<ReglaRecuperada>> m = new HashMap<>();
        DeviceId d = DeviceId.deviceId(dpid);
        for (FlowEntry e : flowRuleService.getFlowEntriesById(appId)) {
            if (!e.deviceId().equals(d)) {
                continue;
            }
            long c = e.id().value();
            long sesion = sesionDeCookie(c);
            if (sesion < 0) {
                continue;
            }
            int restante = (int) Math.max(1, e.hardTimeout() - e.life());
            m.computeIfAbsent(sesion, k -> new ArrayList<>())
                    .add(new ReglaRecuperada(e, sesion, Cookies.indice(c), restante));
        }
        return m;
    }
}
