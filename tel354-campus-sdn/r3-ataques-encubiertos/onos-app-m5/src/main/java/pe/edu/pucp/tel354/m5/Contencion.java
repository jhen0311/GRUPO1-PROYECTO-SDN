package pe.edu.pucp.tel354.m5;

import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.Ip4Prefix;
import org.onlab.packet.TpPort;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.DeviceId;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.meter.Band;
import org.onosproject.net.meter.DefaultBand;
import org.onosproject.net.meter.DefaultMeterRequest;
import org.onosproject.net.meter.Meter;
import org.onosproject.net.meter.MeterId;
import org.onosproject.net.meter.MeterService;
import org.onosproject.net.meter.MeterState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.edu.pucp.tel354.api.Cookies;
import pe.edu.pucp.tel354.api.Pipeline;
import pe.edu.pucp.tel354.api.r3.Destino;
import pe.edu.pucp.tel354.api.r3.TipoAtaque;
import pe.edu.pucp.tel354.api.sesion.SesionInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reglas de contención de la Tabla 11 del LLSD R3 (Tabla 1 del switch de acceso) y meter
 * contencion de 1 Mbps por switch (Tabla 12). Cookie (M5, 0x06, incidente.id, índice), con
 * índice = 4·k + i para la k-ésima sesión del incidente: i = 0 (300), 1 y 2 (301), 3 (302).
 */
public class Contencion {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ApplicationId appId;
    private final MeterService meterService;
    private final Map<DeviceId, MeterId> meters = new ConcurrentHashMap<>();

    public Contencion(ApplicationId appId, MeterService meterService) {
        this.appId = appId;
        this.meterService = meterService;
    }

    private FlowRule regla(DeviceId d, long incidente, int indice, int prioridad, TrafficSelector sel,
                           TrafficTreatment trat, int seg) {
        return DefaultFlowRule.builder().forDevice(d).forTable(Pipeline.T_AUTORIZACION).withPriority(prioridad)
                .withSelector(sel).withTreatment(trat)
                .withCookie(Cookies.cookie(appId, Cookies.CONTENCION, incidente, indice))
                .withHardTimeout(Math.max(1, seg)).build();
    }

    private static TrafficSelector.Builder desde(SesionInfo s) {
        return DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4)
                .matchIPSrc(Ip4Prefix.valueOf(s.ip(), 32));
    }

    /**
     * N1: drop hacia el destino. Escaneo de puertos: solo el destino; insistencia y ataque
     * coordinado (y alertas del IDS): destino, protocolo y puerto intentados.
     */
    public List<FlowRule> n1(long incidente, int k, SesionInfo s, Destino d, TipoAtaque tipo, int seg) {
        TrafficSelector.Builder sel = desde(s).matchIPDst(Ip4Prefix.valueOf(d.ip(), 32));
        boolean conPuerto = tipo != TipoAtaque.ESCANEO_PUERTOS && tipo != TipoAtaque.ESCANEO_RED
                && d.proto() != 0;
        if (conPuerto) {
            sel.matchIPProtocol((byte) d.proto());
            if (d.puertos().size() == 1) {
                int p = d.puertos().iterator().next();
                if (d.proto() == IPv4.PROTOCOL_TCP) {
                    sel.matchTcpDst(TpPort.tpPort(p));
                } else if (d.proto() == IPv4.PROTOCOL_UDP) {
                    sel.matchUdpDst(TpPort.tpPort(p));
                }
            }
        }
        return Collections.singletonList(regla(s.dispositivo(), incidente, 4 * k + Cookies.IDX_300,
                Pipeline.P_CONTENCION, sel.build(), DefaultTrafficTreatment.builder().build(), seg));
    }

    /** N2: 302 DNS -> T2; 301 drop 10.0.0.0/8 y 172.16.0.0/16; 300 meter contencion -> T2. */
    public List<FlowRule> n2(long incidente, int k, SesionInfo s, MeterId contencion, int seg) {
        DeviceId d = s.dispositivo();
        List<FlowRule> l = new ArrayList<>();
        l.add(regla(d, incidente, 4 * k + Cookies.IDX_302, Pipeline.P_N2_DNS,
                desde(s).matchIPDst(Ip4Prefix.valueOf(Pipeline.DNS, 32)).matchIPProtocol(IPv4.PROTOCOL_UDP)
                        .matchUdpDst(TpPort.tpPort(53)).build(),
                DefaultTrafficTreatment.builder().transition(Pipeline.T_ENTREGA).build(), seg));
        l.add(regla(d, incidente, 4 * k + Cookies.IDX_301_USUARIOS, Pipeline.P_N2_AISLA,
                desde(s).matchIPDst(Pipeline.RED_CAMPUS).build(), DefaultTrafficTreatment.builder().build(), seg));
        l.add(regla(d, incidente, 4 * k + Cookies.IDX_301_CONTROL, Pipeline.P_N2_AISLA,
                desde(s).matchIPDst(Pipeline.RED_CONTROL).build(), DefaultTrafficTreatment.builder().build(), seg));
        TrafficTreatment.Builder t = DefaultTrafficTreatment.builder();
        if (contencion != null) {
            t.meter(contencion);
        }
        l.add(regla(d, incidente, 4 * k + Cookies.IDX_300, Pipeline.P_CONTENCION, desde(s).build(),
                t.transition(Pipeline.T_ENTREGA).build(), seg));
        return l;
    }

    /** Copia de la regla con un nuevo hard_timeout (renovación en dos etapas). */
    public static FlowRule conTimeout(FlowRule r, int seg) {
        return DefaultFlowRule.builder().forDevice(r.deviceId()).forTable(r.tableId()).withPriority(r.priority())
                .withSelector(r.selector()).withTreatment(r.treatment()).withCookie(r.id().value())
                .withHardTimeout(Math.max(1, seg)).build();
    }

    /**
     * Meter contencion del switch: el de M5 si ya existe; si no, se crea con MeterService en la
     * primera contención N2 y se espera (hasta 2 s) a que el switch lo confirme.
     */
    public MeterId meterContencion(DeviceId d, int kbps) {
        MeterId m = meters.get(d);
        if (m != null) {
            return m;
        }
        for (Meter x : meterService.getMeters(d)) {
            if (x.appId() != null && x.appId().equals(appId)) {
                meters.put(d, x.id());
                return x.id();
            }
        }
        Band banda = DefaultBand.builder().ofType(Band.Type.DROP).withRate(kbps).burstSize(kbps).build();
        Meter nuevo = meterService.submit(DefaultMeterRequest.builder().forDevice(d).fromApp(appId)
                .withUnit(Meter.Unit.KB_PER_SEC).burst().withBands(Collections.singletonList(banda)).add());
        MeterId id = nuevo.id();
        for (int i = 0; i < 20; i++) {
            for (Meter x : meterService.getMeters(d)) {
                if (x.id().equals(id) && x.state() == MeterState.ADDED) {
                    meters.put(d, id);
                    log.info("M5: meter contencion {} ({} kbps) creado en {}", id, kbps, d);
                    return id;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.warn("M5: el meter contencion de {} no se confirmó en 2 s; se usa de todos modos", d);
        meters.put(d, id);
        return id;
    }

    public void olvidarMeters() {
        meters.clear();
    }
}
