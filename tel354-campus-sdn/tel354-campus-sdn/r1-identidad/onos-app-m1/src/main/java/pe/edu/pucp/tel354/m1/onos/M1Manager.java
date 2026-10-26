package pe.edu.pucp.tel354.m1.onos;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.config.basics.SubjectFactories;
import org.onosproject.net.device.DeviceAdminService;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleEvent;
import org.onosproject.net.flow.FlowRuleListener;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.edu.pucp.tel354.m1.nucleo.M1Nucleo;
import pe.edu.pucp.tel354.m1.nucleo.Parametros;

import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * M1 - Identidad y sesiones (R1) como app de ONOS 1.13.
 *
 * Usa solo servicios nativos del controlador, sin modificar su código:
 *   PacketService      Packet-In de las reglas 900 (EAPOL), 525 (DHCP) y 800 (ARP); Packet-Out
 *   FlowRuleService    reglas de sesión 525/505, 520, 110 y 200/190; aviso de FLOW_REMOVED
 *   DeviceAdminService rebote del puerto al expirar la sesión
 *   NetworkConfig      parámetros (m1-netcfg.json)
 * La lógica está en el paquete nucleo, que no depende de ONOS.
 */
@Component(immediate = true)
public class M1Manager {

    public static final String APP = "tel354.m1";

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceAdminService deviceAdminService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected NetworkConfigRegistry netcfg;

    private ApplicationId appId;
    private PlataformaOnos plataforma;
    private volatile M1Nucleo nucleo;
    /**
     * Hilo propio de M1: arranque con reintentos, eventos de reglas y de configuración.
     * Así M1 nunca bloquea el hilo de eventos de ONOS (que expulsa a los oyentes lentos)
     * con consultas a PostgreSQL, y el núcleo solo se arranca o detiene desde un hilo.
     */
    private ScheduledExecutorService hilo;

    private final PacketProcessor procesador = new Procesador();
    private final FlowRuleListener oyenteReglas = new OyenteReglas();
    private final NetworkConfigListener oyenteConfig = new OyenteConfig();

    private final ConfigFactory<ApplicationId, M1Config> fabrica =
            new ConfigFactory<ApplicationId, M1Config>(SubjectFactories.APP_SUBJECT_FACTORY,
                                                       M1Config.class, M1Config.CLAVE) {
                @Override
                public M1Config createConfig() {
                    return new M1Config();
                }
            };

    @Activate
    protected void activate() {
        appId = coreService.registerApplication(APP);
        plataforma = new PlataformaOnos(appId, packetService, flowRuleService, deviceAdminService);
        hilo = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "m1-eventos");
            t.setDaemon(true);
            return t;
        });
        // Ninguna regla de sesión sobrevive a un reinicio de M1: se parte de cero.
        flowRuleService.removeFlowRulesById(appId);
        netcfg.registerConfigFactory(fabrica);
        netcfg.addListener(oyenteConfig);
        // Si ONOS arranca antes que la topología, 172.16.0.10 (ctl-eth0) aún no existe y los
        // sockets de RADIUS y del relay no se pueden abrir: se reintenta cada 10 s.
        hilo.scheduleWithFixedDelay(this::arrancarNucleo, 0, 10, TimeUnit.SECONDS);
        packetService.addProcessor(procesador, PacketProcessor.director(2));
        flowRuleService.addListener(oyenteReglas);
        log.info("M1 iniciado (appId {})", appId.id());
    }

    @Deactivate
    protected void deactivate() {
        netcfg.removeListener(oyenteConfig);
        flowRuleService.removeListener(oyenteReglas);
        packetService.removeProcessor(procesador);
        hilo.shutdownNow();
        try {
            hilo.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        detenerNucleo();
        flowRuleService.removeFlowRulesById(appId);
        netcfg.unregisterConfigFactory(fabrica);
        log.info("M1 detenido");
    }

    /** Solo desde el hilo de M1 (o en deactivate, con el hilo ya detenido). */
    private void arrancarNucleo() {
        if (nucleo != null) {
            return;
        }
        M1Config cfg = netcfg.getConfig(appId, M1Config.class);
        Parametros p = cfg != null ? cfg.parametros() : new Parametros();
        M1Nucleo n = new M1Nucleo(p, plataforma);
        try {
            n.iniciar();
            nucleo = n;
            log.info("M1: RADIUS {}:{}, Kea {}, relay/NAS {}", p.radiusIp, p.radiusPuerto, p.keaIp, p.relayIp);
        } catch (Exception e) {
            log.warn("M1: núcleo no iniciado ({}); se reintenta en 10 s", e.toString());
            n.detener();
        }
    }

    private void detenerNucleo() {
        M1Nucleo n = nucleo;
        nucleo = null;
        if (n != null) {
            n.detener();
        }
    }

    /** Copia los bytes de la trama recibida (o la vuelve a serializar si no vienen). */
    private static byte[] bytes(InboundPacket pkt) {
        ByteBuffer b = pkt.unparsed();
        if (b != null) {
            ByteBuffer d = b.duplicate();
            d.rewind();
            byte[] t = new byte[d.remaining()];
            d.get(t);
            return t;
        }
        return pkt.parsed() != null ? pkt.parsed().serialize() : null;
    }

    private class Procesador implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            M1Nucleo n = nucleo;
            if (n == null || context.isHandled()) {
                return;
            }
            InboundPacket pkt = context.inPacket();
            if (pkt.receivedFrom() == null || pkt.receivedFrom().port() == null
                    || pkt.receivedFrom().port().isLogical()) {
                return;                                    // solo tramas de puertos físicos
            }
            if (n.procesarTrama(pkt.receivedFrom().deviceId().toString(),
                                (int) pkt.receivedFrom().port().toLong(), bytes(pkt))) {
                context.block();                           // EAPOL, DHCP o ARP atendido por M1
            }
        }
    }

    /**
     * RULE_REMOVED de una regla de sesión. El evento también llega por las reglas que retira
     * el propio M1; el gestor solo cierra la sesión si la regla sigue siendo suya (hard_timeout).
     */
    private class OyenteReglas implements FlowRuleListener {
        @Override
        public void event(FlowRuleEvent event) {
            if (event.type() != FlowRuleEvent.Type.RULE_REMOVED) {
                return;
            }
            long id = event.subject().id().value();
            long sesion = plataforma.sesionDeCookie(id);
            if (sesion < 0) {
                return;
            }
            hilo.execute(() -> {
                M1Nucleo n = nucleo;
                if (n != null) {
                    n.gestor().alExpirar(sesion, regla -> regla instanceof FlowRule
                            && ((FlowRule) regla).id().value() == id);
                }
            });
        }
    }

    private class OyenteConfig implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            if (event.configClass() != M1Config.class) {
                return;
            }
            if (event.type() == NetworkConfigEvent.Type.CONFIG_ADDED
                    || event.type() == NetworkConfigEvent.Type.CONFIG_UPDATED
                    || event.type() == NetworkConfigEvent.Type.CONFIG_REMOVED) {
                hilo.execute(() -> {
                    log.info("M1: configuración cambiada; se reinicia el núcleo");
                    detenerNucleo();
                    arrancarNucleo();      // si falla, el reintento periódico lo vuelve a intentar
                });
            }
        }
    }
}
