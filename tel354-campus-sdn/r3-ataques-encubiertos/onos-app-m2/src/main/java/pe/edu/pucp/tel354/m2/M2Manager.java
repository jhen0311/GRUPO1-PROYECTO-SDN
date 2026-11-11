package pe.edu.pucp.tel354.m2;

import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.Ip4Prefix;
import org.onlab.packet.TCP;
import org.onlab.packet.TpPort;
import org.onlab.packet.UDP;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.event.EventDeliveryService;
import org.onosproject.event.ListenerRegistry;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.edu.pucp.tel354.api.Cookies;
import pe.edu.pucp.tel354.api.Pipeline;
import pe.edu.pucp.tel354.api.r3.AuthorizationEvent;
import pe.edu.pucp.tel354.api.r3.AuthorizationListener;
import pe.edu.pucp.tel354.api.r3.AuthorizationService;
import pe.edu.pucp.tel354.api.sesion.SesionInfo;
import pe.edu.pucp.tel354.api.sesion.SessionEvent;
import pe.edu.pucp.tel354.api.sesion.SessionListener;
import pe.edu.pucp.tel354.api.sesion.SessionService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * M2 - Slow-path de la regla 55 (LLSD R3, sección 5.3). Plantilla de partida.
 *
 * Atiende solo IPv4 con etiqueta VLAN que llega de un puerto de acceso (la regla 520 la puso y la
 * captura 55 la envió al controlador). Si la sesión es válida y la matriz no permite el acceso,
 * instala un DROP 150 de 8 s para la cuádrupla y publica AuthorizationDenied. Con SessionClosed
 * retira los DROP 150 de esa sesión. La matriz se lee de v_permiso_efectivo y v_acceso_curso
 * (rol onos_m2) en la clase Matriz (pendiente: completar con la caché y policy_changed).
 */
@Component(immediate = true, service = AuthorizationService.class)
public class M2Manager implements AuthorizationService {

    public static final String APP = "tel354.m2";

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected SessionService sessionService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected EventDeliveryService eventDispatcher;

    private ApplicationId appId;
    private ExecutorService hilo;
    private final AtomicInteger contador = new AtomicInteger();
    private final ListenerRegistry<AuthorizationEvent, AuthorizationListener> oyentes = new ListenerRegistry<>();
    private final PacketProcessor procesador = new Procesador();
    private final SessionListener oyenteSesiones = e -> {
        if (e.type() == SessionEvent.Type.SESSION_CLOSED) {
            hilo.execute(() -> retirarDrops(e.subject().id()));
        }
    };
    /** Matriz de autorización en caché (rol, destino, protocolo, puerto) y cursos por usuario. */
    private final Matriz matriz = new Matriz();

    @Activate
    protected void activate() {
        appId = coreService.registerApplication(APP);
        hilo = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "m2-slowpath");
            t.setDaemon(true);
            return t;
        });
        eventDispatcher.addSink(AuthorizationEvent.class, oyentes);
        sessionService.addListener(oyenteSesiones);
        packetService.addProcessor(procesador, PacketProcessor.director(3));   // después de M1 (2)
        log.info("M2 iniciado (appId {})", appId.id());
    }

    @Deactivate
    protected void deactivate() {
        packetService.removeProcessor(procesador);
        sessionService.removeListener(oyenteSesiones);
        hilo.shutdownNow();
        flowRuleService.removeFlowRulesById(appId);
        eventDispatcher.removeSink(AuthorizationEvent.class);
        log.info("M2 detenido");
    }

    @Override
    public void addListener(AuthorizationListener l) {
        oyentes.addListener(l);
    }

    @Override
    public void removeListener(AuthorizationListener l) {
        oyentes.removeListener(l);
    }

    private class Procesador implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }
            InboundPacket pkt = context.inPacket();
            Ethernet eth = pkt.parsed();
            if (eth == null || eth.getVlanID() == Ethernet.VLAN_UNTAGGED || eth.getEtherType() != Ethernet.TYPE_IPV4
                    || pkt.receivedFrom().port().toLong() == 1) {      // puerto 1 = uplink del switch de acceso
                return;
            }
            IPv4 ip = (IPv4) eth.getPayload();
            ConnectPoint cp = pkt.receivedFrom();
            context.block();                                          // el Packet-In de la 55 no sigue
            hilo.execute(() -> denegar(cp, ip));
        }
    }

    private void denegar(ConnectPoint cp, IPv4 ip) {
        Ip4Address origen = Ip4Address.valueOf(ip.getSourceAddress());
        Ip4Address destino = Ip4Address.valueOf(ip.getDestinationAddress());
        SesionInfo s = sessionService.getSessionByIp(origen);
        if (s == null || !s.dispositivo().equals(cp.deviceId()) || !s.puerto().equals(cp.port())) {
            return;                                                   // no corresponde a una sesión válida
        }
        int proto = ip.getProtocol() & 0xff;
        int puerto = -1;
        if (ip.getPayload() instanceof TCP) {
            puerto = ((TCP) ip.getPayload()).getDestinationPort();
        } else if (ip.getPayload() instanceof UDP) {
            puerto = ((UDP) ip.getPayload()).getDestinationPort();
        }
        if (matriz.permite(s, destino, proto, puerto)) {
            // Carrera con un cambio de política: reinstalar el permiso y hacer Packet-Out (pendiente)
            log.debug("M2: {} -> {} permitido por la matriz", s, destino);
            return;
        }
        TrafficSelector.Builder sel = DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4)
                .matchIPSrc(Ip4Prefix.valueOf(origen, 32)).matchIPDst(Ip4Prefix.valueOf(destino, 32))
                .matchIPProtocol((byte) proto);
        if (proto == 6 && puerto >= 0) {
            sel.matchTcpDst(TpPort.tpPort(puerto));
        } else if (proto == 17 && puerto >= 0) {
            sel.matchUdpDst(TpPort.tpPort(puerto));
        }
        FlowRule drop = DefaultFlowRule.builder().forDevice(cp.deviceId()).forTable(Pipeline.T_AUTORIZACION)
                .withPriority(Pipeline.P_DENEGADO).withSelector(sel.build())
                .withTreatment(DefaultTrafficTreatment.builder().build())
                .withCookie(Cookies.cookie(appId, Cookies.SESION, s.id(), contador.getAndIncrement() & 0xff))
                .withHardTimeout(Pipeline.HARD_DENEGADO_SEG).build();
        flowRuleService.applyFlowRules(drop);
        AuthorizationEvent e = new AuthorizationEvent(s, destino, proto, puerto, cp.deviceId());
        log.debug("M2: {}", e);
        eventDispatcher.post(e);
    }

    /** Al cerrarse la sesión, sus DROP 150 no deben afectar a otro usuario que reciba la misma IP. */
    private void retirarDrops(long sesionId) {
        List<FlowRule> l = new ArrayList<>();
        for (FlowEntry e : flowRuleService.getFlowEntriesById(appId)) {
            long c = e.id().value();
            if (Cookies.tipo(c) == Cookies.SESION && Cookies.id(c) == sesionId) {
                l.add(e);
            }
        }
        if (!l.isEmpty()) {
            flowRuleService.removeFlowRules(l.toArray(new FlowRule[0]));
        }
    }
}
