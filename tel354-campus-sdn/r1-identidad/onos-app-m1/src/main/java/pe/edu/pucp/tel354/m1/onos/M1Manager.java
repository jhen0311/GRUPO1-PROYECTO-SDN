package pe.edu.pucp.tel354.m1.onos;

import org.onlab.packet.Ip4Address;
import org.onlab.packet.MacAddress;
import org.onosproject.app.ApplicationService;
import org.onosproject.app.ApplicationState;
import org.onosproject.cluster.ClusterService;
import org.onosproject.cluster.ControllerNode;
import org.onosproject.cluster.NodeId;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.event.EventDeliveryService;
import org.onosproject.event.ListenerRegistry;
import org.onosproject.mastership.MastershipEvent;
import org.onosproject.mastership.MastershipListener;
import org.onosproject.mastership.MastershipService;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.config.basics.SubjectFactories;
import org.onosproject.net.device.DeviceAdminService;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleEvent;
import org.onosproject.net.flow.FlowRuleListener;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.store.cluster.messaging.ClusterCommunicationService;
import org.onosproject.store.cluster.messaging.MessageSubject;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.edu.pucp.tel354.api.sesion.EstadoSesion;
import pe.edu.pucp.tel354.api.sesion.Motivo;
import pe.edu.pucp.tel354.api.sesion.SesionInfo;
import pe.edu.pucp.tel354.api.sesion.SessionEvent;
import pe.edu.pucp.tel354.api.sesion.SessionListener;
import pe.edu.pucp.tel354.api.sesion.SessionService;
import pe.edu.pucp.tel354.m1.nucleo.BaseDatos;
import pe.edu.pucp.tel354.m1.nucleo.M1Nucleo;
import pe.edu.pucp.tel354.m1.nucleo.Notificador;
import pe.edu.pucp.tel354.m1.nucleo.Parametros;
import pe.edu.pucp.tel354.m1.nucleo.Sesion;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * M1 - Identidad y sesiones (R1) como app de ONOS 2.7, en un clúster de 3 instancias.
 *
 * Servicios nativos del controlador, sin modificar su código:
 *   PacketService       Packet-In de las reglas 900 (EAPOL), 525 (DHCP), 505 (otra IP) y 800 (ARP)
 *   FlowRuleService     reglas de sesión 525/505, 520, 110 y 200/190; RULE_REMOVED; meter ctl_P
 *   DeviceAdminService  rebote del puerto; PORT_UPDATED (enlace caído)
 *   MastershipService   cada instancia atiende solo los switches de los que es master
 *   ClusterCommunication revocar y aplicarCuarentena se ejecutan en la instancia master
 *   EventDeliveryService publicación de SessionEvent (tel354-api)
 *   NetworkConfig       parámetros (m1-netcfg.json)
 * La lógica está en el paquete nucleo, que no depende de ONOS.
 */
@Component(immediate = true, service = {SessionService.class, M1Consola.class})
public class M1Manager implements SessionService, M1Consola {

    public static final String APP = "tel354.m1";
    private static final String HILO = "m1-eventos";
    private static final MessageSubject REVOCAR = new MessageSubject("tel354-m1-revocar");
    private static final MessageSubject CUARENTENA = new MessageSubject("tel354-m1-cuarentena");

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ApplicationService applicationService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceAdminService deviceAdminService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected MastershipService mastershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ClusterService clusterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ClusterCommunicationService comunicacion;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected EventDeliveryService eventDispatcher;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry netcfg;

    private ApplicationId appId;
    private NodeId local;
    private PlataformaOnos plataforma;
    private volatile M1Nucleo nucleo;
    private volatile Parametros parametros = new Parametros();
    /**
     * Hilo propio de M1: arranque con reintentos, eventos de reglas, de maestría y de
     * configuración, y operaciones reenviadas por el clúster. M1 nunca bloquea el hilo de
     * eventos de ONOS (que expulsa a los oyentes lentos) con consultas a PostgreSQL.
     */
    private ScheduledExecutorService hilo;
    /** Rebotes de puerto (bajar, esperar t_down, subir). */
    private ScheduledExecutorService temporizador;

    private final ListenerRegistry<SessionEvent, SessionListener> oyentes = new ListenerRegistry<>();
    private final PacketProcessor procesador = new Procesador();
    private final FlowRuleListener oyenteReglas = new OyenteReglas();
    private final NetworkConfigListener oyenteConfig = new OyenteConfig();
    private final MastershipListener oyenteMaestria = new OyenteMaestria();
    private final DeviceListener oyenteDispositivos = new OyenteDispositivos();
    private final Notificador aviso = new Aviso();

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
        local = clusterService.getLocalNode().id();
        hilo = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, HILO);
            t.setDaemon(true);
            return t;
        });
        temporizador = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "m1-rebotes");
            t.setDaemon(true);
            return t;
        });
        plataforma = new PlataformaOnos(appId, packetService, flowRuleService, deviceAdminService, temporizador);
        eventDispatcher.addSink(SessionEvent.class, oyentes);
        // Ya no se borran todas las reglas de M1 al activar: en el clúster, otra instancia
        // puede estar atendiendo sesiones vigentes; cada master reconstruye las suyas.
        netcfg.registerConfigFactory(fabrica);
        netcfg.addListener(oyenteConfig);
        comunicacion.<String, String>addSubscriber(REVOCAR, M1Manager::texto, this::revocarLocal,
                                                   M1Manager::bytes, hilo);
        comunicacion.<String, String>addSubscriber(CUARENTENA, M1Manager::texto, this::cuarentenaLocal,
                                                   M1Manager::bytes, hilo);
        // Si ONOS arranca antes que la red in-band, la IP de esta instancia aún no es alcanzable
        // y los sockets de RADIUS y del relay no se pueden abrir: se reintenta cada 10 s.
        hilo.scheduleWithFixedDelay(this::arrancarNucleo, 0, 10, TimeUnit.SECONDS);
        packetService.addProcessor(procesador, PacketProcessor.director(2));
        flowRuleService.addListener(oyenteReglas);
        mastershipService.addListener(oyenteMaestria);
        deviceService.addListener(oyenteDispositivos);
        log.info("M1 iniciado en {} (appId {})", local, appId.id());
    }

    @Deactivate
    protected void deactivate() {
        // Desactivar la app (en todo el clúster) cierra las sesiones; apagar solo esta instancia
        // las deja para que el nuevo master las adopte.
        boolean appDesactivada = applicationService.getState(appId) != ApplicationState.ACTIVE;
        comunicacion.removeSubscriber(REVOCAR);
        comunicacion.removeSubscriber(CUARENTENA);
        deviceService.removeListener(oyenteDispositivos);
        mastershipService.removeListener(oyenteMaestria);
        netcfg.removeListener(oyenteConfig);
        flowRuleService.removeListener(oyenteReglas);
        packetService.removeProcessor(procesador);
        hilo.shutdownNow();
        try {
            hilo.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        detenerNucleo(appDesactivada);
        temporizador.shutdownNow();
        if (appDesactivada) {
            flowRuleService.removeFlowRulesById(appId);
        }
        eventDispatcher.removeSink(SessionEvent.class);
        netcfg.unregisterConfigFactory(fabrica);
        log.info("M1 detenido en {} ({})", local, appDesactivada ? "app desactivada" : "instancia apagada");
    }

    // ------------------------------------------------------------------ núcleo

    /** Solo desde el hilo de M1 (o en deactivate, con el hilo ya detenido). */
    private void arrancarNucleo() {
        if (nucleo != null) {
            return;
        }
        M1Config cfg = netcfg.getConfig(appId, M1Config.class);
        Parametros p = cfg != null ? cfg.parametros(local.id()) : new Parametros();
        M1Nucleo n = new M1Nucleo(p, plataforma, aviso);
        try {
            n.iniciar();
        } catch (Exception e) {
            log.warn("M1: núcleo no iniciado en {} ({}); se reintenta en 10 s", local, e.toString());
            n.detener(false);
            return;
        }
        n.gestor().cierreRemoto(this::cierreRemoto);
        parametros = p;
        nucleo = n;
        log.info("M1: {} con NAS/relay {}, RADIUS {}:{}, Kea {}", local, p.nasIp, p.radiusIp, p.radiusPuerto, p.keaIp);
        for (Device d : deviceService.getAvailableDevices()) {
            if (mastershipService.isLocalMaster(d.id())) {
                adoptar(d.id());
            }
        }
    }

    private void detenerNucleo(boolean cerrarSesiones) {
        M1Nucleo n = nucleo;
        nucleo = null;
        if (n != null) {
            n.detener(cerrarSesiones);
        }
    }

    private void adoptar(DeviceId d) {
        M1Nucleo n = nucleo;
        if (n == null) {
            return;
        }
        try {
            int k = n.gestor().adoptar(d.toString());
            if (k > 0) {
                log.info("M1: {} es master de {}: {} sesiones recuperadas", local, d, k);
            }
        } catch (Exception e) {
            log.warn("M1: no se pudieron recuperar las sesiones de {}: {}", d, e.toString());
        }
    }

    /** Ejecuta en el hilo de M1 (o directamente si ya se está en él) y espera el resultado. */
    private <T> T enHilo(Callable<T> c, T siFalla) {
        try {
            if (HILO.equals(Thread.currentThread().getName())) {
                return c.call();
            }
            return hilo.submit(c).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("M1: operación fallida: {}", e.toString());
            return siFalla;
        }
    }

    private static String texto(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------- SessionService (P5)

    @Override
    public void addListener(SessionListener l) {
        oyentes.addListener(l);
    }

    @Override
    public void removeListener(SessionListener l) {
        oyentes.removeListener(l);
    }

    @Override
    public SesionInfo getSessionByIp(Ip4Address ip) {
        M1Nucleo n = nucleo;
        if (n == null || ip == null) {
            return null;
        }
        Sesion s = n.gestor().buscarPorIp(ip.toString());
        if (s != null) {
            return info(s);
        }
        try {
            BaseDatos.FilaSesion f = n.gestor().bd().sesionAbiertaPorIp(ip.toString());
            return f != null ? info(f) : null;
        } catch (Exception e) {
            log.warn("M1: getSessionByIp({}): {}", ip, e.toString());
            return null;
        }
    }

    @Override
    public List<SesionInfo> getSessionsByUser(int usuarioId) {
        M1Nucleo n = nucleo;
        List<SesionInfo> l = new ArrayList<>();
        if (n == null) {
            return l;
        }
        try {
            for (BaseDatos.FilaSesion f : n.gestor().bd().sesionesAbiertasDeUsuario(usuarioId)) {
                Sesion s = n.gestor().buscarPorId(f.id);
                l.add(s != null ? info(s) : info(f));
            }
        } catch (Exception e) {
            log.warn("M1: getSessionsByUser({}): {}", usuarioId, e.toString());
        }
        return l;
    }

    @Override
    public SesionInfo getSession(long sesionId) {
        M1Nucleo n = nucleo;
        if (n == null) {
            return null;
        }
        Sesion s = n.gestor().buscarPorId(sesionId);
        if (s != null) {
            return info(s);
        }
        try {
            BaseDatos.FilaSesion f = n.gestor().bd().sesion(sesionId);
            return f != null ? info(f) : null;
        } catch (Exception e) {
            log.warn("M1: getSession({}): {}", sesionId, e.toString());
            return null;
        }
    }

    @Override
    public boolean revocar(long sesionId, Motivo motivo, String solicitante) {
        SesionInfo s = getSession(sesionId);
        if (s == null || s.estado() == EstadoSesion.CERRADA) {
            return false;
        }
        log.info("M1: revocación de la sesión {} ({}) pedida por {}", sesionId, motivo, solicitante);
        NodeId master = mastershipService.getMasterFor(s.dispositivo());
        String msg = sesionId + "|" + motivo.name() + "|" + solicitante;
        if (master == null || master.equals(local)) {
            return "true".equals(enHilo(() -> revocarLocal(msg), "false"));
        }
        try {
            return "true".equals(comunicacion.<String, String>sendAndReceive(msg, REVOCAR, M1Manager::bytes,
                                                                             M1Manager::texto, master)
                                         .get(15, TimeUnit.SECONDS));
        } catch (Exception e) {
            log.warn("M1: {} no respondió a la revocación de {}: {}", master, sesionId, e.toString());
            return false;
        }
    }

    @Override
    public int aplicarCuarentena(int usuarioId, long incidenteId) {
        String msg = usuarioId + "|" + incidenteId;
        int total = Integer.parseInt(enHilo(() -> cuarentenaLocal(msg), "0"));
        for (ControllerNode nodo : clusterService.getNodes()) {
            if (nodo.id().equals(local) || !clusterService.getState(nodo.id()).isActive()) {
                continue;
            }
            try {
                total += Integer.parseInt(comunicacion.<String, String>sendAndReceive(
                        msg, CUARENTENA, M1Manager::bytes, M1Manager::texto, nodo.id()).get(15, TimeUnit.SECONDS));
            } catch (Exception e) {
                log.warn("M1: {} no respondió al paso a cuarentena del usuario {}: {}", nodo.id(), usuarioId,
                         e.toString());
            }
        }
        log.info("M1: incidente {}: {} sesiones del usuario {} pasan a cuarentena", incidenteId, total, usuarioId);
        return total;
    }

    /** "sesionId|MOTIVO|solicitante", en el hilo de M1 de la instancia master. */
    private String revocarLocal(String msg) {
        String[] c = msg.split("\\|", 3);
        M1Nucleo n = nucleo;
        if (n == null) {
            return "false";
        }
        long id = Long.parseLong(c[0]);
        boolean ok = n.gestor().revocar(id, c[1]);
        if (!ok) {
            try {                                       // sesión sin reglas ni memoria: solo la BD
                n.gestor().bd().cerrarSesion(id, c[1]);
            } catch (Exception e) {
                log.warn("M1: no se pudo cerrar {} en la BD: {}", id, e.toString());
            }
        }
        return Boolean.toString(ok);
    }

    /** "usuarioId|incidenteId", en el hilo de M1 de cada instancia. */
    private String cuarentenaLocal(String msg) {
        String[] c = msg.split("\\|");
        M1Nucleo n = nucleo;
        if (n == null) {
            return "0";
        }
        return Integer.toString(n.gestor().aplicarCuarentena(Integer.parseInt(c[0]), n.kea()));
    }

    /** Sesión de otra instancia que este M1 debe cerrar (MOVILIDAD o LIMITE_SESIONES). */
    private void cierreRemoto(long sesionId, String motivo) {
        hilo.execute(() -> revocar(sesionId, Motivo.valueOf(motivo), "M1@" + local.id()));
    }

    // ----------------------------------------------------------------- consola

    @Override
    public List<SesionInfo> sesionesLocales() {
        M1Nucleo n = nucleo;
        List<SesionInfo> l = new ArrayList<>();
        if (n != null) {
            for (Sesion s : n.gestor().sesiones()) {
                l.add(info(s));
            }
        }
        return l;
    }

    @Override
    public List<SesionInfo> sesionesAbiertas() {
        M1Nucleo n = nucleo;
        if (n == null) {
            return Collections.emptyList();
        }
        List<SesionInfo> l = new ArrayList<>();
        try {
            for (BaseDatos.FilaSesion f : n.gestor().bd().sesionesAbiertasTodas()) {
                l.add(info(f));
            }
        } catch (Exception e) {
            log.warn("M1: sesionesAbiertas: {}", e.toString());
        }
        return l;
    }

    @Override
    public String nasIp() {
        return nucleo != null ? parametros.nasIp : "(núcleo no iniciado)";
    }

    // --------------------------------------------------------------- conversión

    static SesionInfo info(Sesion s) {
        return SesionInfo.builder().id(s.id).usuarioId(s.usuarioId).usuario(s.usuario).rol(s.rol)
                .octeto(s.octeto).pabellon(s.pabellon).mac(MacAddress.valueOf(s.mac))
                .dispositivo(DeviceId.deviceId(s.dpid)).puerto(PortNumber.portNumber(s.puerto))
                .ip(s.ip != null ? Ip4Address.valueOf(s.ip) : null).vlan(s.vlan)
                .estado(EstadoSesion.valueOf(s.estado.name())).inicioMs(s.inicioMs).build();
    }

    static SesionInfo info(BaseDatos.FilaSesion f) {
        return info(f.aSesion());
    }

    /** Notificador del núcleo -> SessionEvent de tel354-api. */
    private class Aviso implements Notificador {
        @Override
        public void sesionLista(Sesion s) {
            eventDispatcher.post(SessionEvent.lista(info(s)));
        }

        @Override
        public void sesionCerrada(Sesion s, String motivo) {
            eventDispatcher.post(SessionEvent.cerrada(info(s), Motivo.valueOf(motivo)));
        }

        @Override
        public void autenticacionFallida(String dpid, int puerto, String mac, String usuario) {
            eventDispatcher.post(SessionEvent.falloAutenticacion(SesionInfo.builder().usuario(usuario)
                    .mac(MacAddress.valueOf(mac)).dispositivo(DeviceId.deviceId(dpid))
                    .puerto(PortNumber.portNumber(puerto)).estado(EstadoSesion.CERRADA).build()));
        }

        @Override
        public void violacion(Sesion s, String tipo, String ipUsada, boolean inequivoca) {
            eventDispatcher.post(SessionEvent.violacion(info(s), SessionEvent.Violacion.valueOf(tipo),
                                                        Ip4Address.valueOf(ipUsada), inequivoca));
        }

        @Override
        public void sondeoArp(Sesion s, String ipBuscada) {
            eventDispatcher.post(SessionEvent.sondeoArp(info(s), Ip4Address.valueOf(ipBuscada)));
        }
    }

    // ------------------------------------------------------------------ oyentes

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
                context.block();                           // EAPOL, DHCP, ARP o 505 atendido por M1
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

    /** Ganar la maestría de un switch: reconstruir sus sesiones; perderla: olvidarlas. */
    private class OyenteMaestria implements MastershipListener {
        @Override
        public void event(MastershipEvent event) {
            if (event.type() != MastershipEvent.Type.MASTER_CHANGED) {
                return;
            }
            DeviceId d = event.subject();
            hilo.execute(() -> {
                M1Nucleo n = nucleo;
                if (n == null) {
                    return;
                }
                if (mastershipService.isLocalMaster(d)) {
                    adoptar(d);
                } else {
                    int k = n.gestor().soltar(d.toString());
                    if (k > 0) {
                        log.info("M1: {} dejó de ser master de {}: {} sesiones pasan a otra instancia", local, d, k);
                    }
                }
            });
        }
    }

    /** Enlace caído: las sesiones del puerto se cierran con DESCONEXION. */
    private class OyenteDispositivos implements DeviceListener {
        @Override
        public void event(DeviceEvent event) {
            if (event.type() != DeviceEvent.Type.PORT_UPDATED || event.port() == null
                    || event.port().isEnabled()) {
                return;
            }
            DeviceId d = event.subject().id();
            if (!mastershipService.isLocalMaster(d)) {
                return;
            }
            int puerto = (int) event.port().number().toLong();
            hilo.execute(() -> {
                M1Nucleo n = nucleo;
                if (n != null && n.gestor().puertoCaido(d.toString(), puerto) > 0) {
                    log.info("M1: enlace caído en {}/{}", d, puerto);
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
                    log.info("M1: configuración cambiada; se reinicia el núcleo (las sesiones se recuperan)");
                    detenerNucleo(false);
                    arrancarNucleo();      // si falla, el reintento periódico lo vuelve a intentar
                });
            }
        }
    }
}
