package pe.edu.pucp.tel354.m2;

import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.Ip4Prefix;
import org.onlab.packet.TCP;
import org.onlab.packet.UDP;
import org.onosproject.app.ApplicationService;
import org.onosproject.app.ApplicationState;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.event.EventDeliveryService;
import org.onosproject.event.ListenerRegistry;
import org.onosproject.mastership.MastershipService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.config.basics.SubjectFactories;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleOperations;
import org.onosproject.net.flow.FlowRuleOperationsContext;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.PortCriterion;
import org.onosproject.net.flow.instructions.Instructions;
import org.onosproject.net.meter.Band;
import org.onosproject.net.meter.DefaultBand;
import org.onosproject.net.meter.DefaultMeterRequest;
import org.onosproject.net.meter.Meter;
import org.onosproject.net.meter.MeterCellId;
import org.onosproject.net.meter.MeterId;
import org.onosproject.net.meter.MeterRequest;
import org.onosproject.net.meter.MeterService;
import org.onosproject.net.meter.MeterState;
import org.onosproject.net.packet.DefaultOutboundPacket;
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
import pe.edu.pucp.tel354.api.sesion.EstadoSesion;
import pe.edu.pucp.tel354.api.sesion.SesionInfo;
import pe.edu.pucp.tel354.api.sesion.SessionEvent;
import pe.edu.pucp.tel354.api.sesion.SessionListener;
import pe.edu.pucp.tel354.api.sesion.SessionService;
import pe.edu.pucp.tel354.m2.nucleo.Politica;
import pe.edu.pucp.tel354.m2.nucleo.ReglaT1;
import pe.edu.pucp.tel354.m2.nucleo.Tabla1;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M2 · Autorización (R2) como app de ONOS 2.7.0: tel354.m2.
 *
 * <ol>
 *   <li><b>Tabla 1 proactiva.</b> Al detectar cada switch de acceso (el que tiene la regla 600 de M7)
 *       instala los permisos 100 (v_permiso_efectivo) y las comunes 60, 58, 57, 55, 50 y 0, con los
 *       meters captura y cuarentena. Concilia de forma idempotente: compara lo deseado con lo que ya
 *       está en el almacén de ONOS y solo envía la diferencia. Reemplaza al puente
 *       base_auto.py --solo-tabla1.</li>
 *   <li><b>policy_changed.</b> Un cambio en permiso_rol, recurso o inscripcion avisa por
 *       LISTEN/NOTIFY: M2 relee la matriz y reprograma solo lo que cambió, sin reconfigurar switches
 *       a mano. Como respaldo, la matriz se relee cada conciliarSeg.</li>
 *   <li><b>Slow-path de la regla 55</b> (LLSD R3, sección 5.3). IPv4 con VLAN desde un puerto de host:
 *       si la sesión es válida y la matriz no lo permite, DROP 150 de 8 s y AuthorizationDenied; si
 *       la matriz sí lo permite (carrera con un cambio de política), reinstala el permiso y reenvía el
 *       paquete. Con SessionClosed retira las reglas de esa sesión.</li>
 * </ol>
 *
 * Hilos: el procesador de paquetes y los listeners solo encolan; todo corre en el hilo "m2" (Tabla 1
 * y slow-path), la lectura de la BD en "m2-bd" y el LISTEN en "m2-listen". En el clúster, solo la
 * instancia master de cada switch lo programa (y es la que recibe sus Packet-In).
 */
@Component(immediate = true, service = {AuthorizationService.class, M2Manager.class})
public class M2Manager implements AuthorizationService {

    public static final String APP = "tel354.m2";
    /** appId de M7 (tel354.base): su regla 600 marca un switch de acceso y su puerto de uplink. */
    public static final String APP_M7 = "tel354.m7";
    /** appId del puente REST de la Tabla 1: si sigue en un switch, M2 no la programa ahí. */
    public static final String APP_PUENTE = "tel354.tabla1";

    /** Índices de la cookie 0x02 de M2: 0–127 para los DROP 150, 128–255 para las 110 de carrera. */
    static final int MAX_DROP = 128;
    static final int PRIMER_110 = 128;
    private static final long ESPERA_METER_MS = 1000;
    private static final long REENVIO_PERMITIDO_MS = 2000;

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
    protected MeterService meterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected MastershipService mastershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry netcfg;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected SessionService sessionService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected EventDeliveryService eventDispatcher;

    /** Lo que M2 sabe de cada switch de acceso. */
    static final class Estado {
        volatile int uplink = -1;
        volatile String nota = "";
        volatile int deseadas;
        volatile long completaMs = -1;
        volatile MeterId captura;
        volatile MeterId cuarentena;
        volatile long capturaPedidoMs;
        volatile long cuarentenaPedidoMs;
        final Map<Long, Integer> reemplazos = new ConcurrentHashMap<>();
    }

    private ApplicationId appId;
    private volatile M2Config.Valores cfg = new M2Config.Valores();
    private volatile Politica politica;
    private volatile boolean bdCaida;
    private volatile BaseDatosM2.Escucha escucha;
    private ScheduledExecutorService hilo;
    private ExecutorService hiloBd;
    private ScheduledFuture<?> periodica;
    private final Map<DeviceId, Estado> estados = new ConcurrentHashMap<>();
    private final Set<DeviceId> pendientes = ConcurrentHashMap.newKeySet();
    /** Cuádruplas recién atendidas (sesión|destino|proto|puerto → vence en ms): una por DROP vigente. */
    private final Map<String, Long> recientes = new ConcurrentHashMap<>();
    private final AtomicInteger contador = new AtomicInteger();
    private final AtomicLong denegaciones = new AtomicLong();
    private final AtomicLong carreras = new AtomicLong();
    private final ListenerRegistry<AuthorizationEvent, AuthorizationListener> oyentes = new ListenerRegistry<>();

    private final ConfigFactory<ApplicationId, M2Config> fabrica =
            new ConfigFactory<ApplicationId, M2Config>(SubjectFactories.APP_SUBJECT_FACTORY, M2Config.class,
                                                       M2Config.CLAVE) {
                @Override
                public M2Config createConfig() {
                    return new M2Config();
                }
            };

    private final PacketProcessor procesador = new Procesador();
    private final DeviceListener oyenteDispositivos = this::alDispositivo;
    private final NetworkConfigListener oyenteConfig = this::alConfig;
    private final SessionListener oyenteSesiones = e -> {
        if (e.type() == SessionEvent.Type.SESSION_CLOSED) {
            enHilo(() -> retirarDeSesion(e.subject().id()), 0);
        }
    };

    // ------------------------------------------------------------------ ciclo de vida

    @Activate
    protected void activate() {
        appId = coreService.registerApplication(APP);
        hilo = Executors.newSingleThreadScheduledExecutor(r -> hiloDemonio(r, "m2"));
        hiloBd = Executors.newSingleThreadExecutor(r -> hiloDemonio(r, "m2-bd"));
        eventDispatcher.addSink(AuthorizationEvent.class, oyentes);
        netcfg.registerConfigFactory(fabrica);
        netcfg.addListener(oyenteConfig);
        deviceService.addListener(oyenteDispositivos);
        sessionService.addListener(oyenteSesiones);
        packetService.addProcessor(procesador, PacketProcessor.director(3));   // después de M1 (2)
        enHilo(this::configurar, 0);
        log.info("M2 iniciado (appId {})", appId.id());
    }

    @Deactivate
    protected void deactivate() {
        boolean appDesactivada = applicationService.getState(appId) != ApplicationState.ACTIVE;
        packetService.removeProcessor(procesador);
        sessionService.removeListener(oyenteSesiones);
        deviceService.removeListener(oyenteDispositivos);
        netcfg.removeListener(oyenteConfig);
        detenerEscucha();
        hilo.shutdownNow();
        hiloBd.shutdownNow();
        if (appDesactivada) {
            // Desactivar la app retira la Tabla 1, sus meters y los DROP 150 / 110 de carrera.
            flowRuleService.removeFlowRulesById(appId);
            int n = 0;
            for (Meter m : meterService.getAllMeters()) {
                if (appId.equals(m.appId())) {
                    retirarMeter(m);
                    n++;
                }
            }
            log.info("M2: reglas y {} meters retirados", n);
        }
        eventDispatcher.removeSink(AuthorizationEvent.class);
        netcfg.unregisterConfigFactory(fabrica);
        estados.clear();
        log.info("M2 detenido ({})", appDesactivada ? "app desactivada" : "instancia apagada");
    }

    @Override
    public void addListener(AuthorizationListener l) {
        oyentes.addListener(l);
    }

    @Override
    public void removeListener(AuthorizationListener l) {
        oyentes.removeListener(l);
    }

    private static Thread hiloDemonio(Runnable r, String nombre) {
        Thread t = new Thread(r, nombre);
        t.setDaemon(true);
        return t;
    }

    private void enHilo(Runnable r, long ms) {
        ScheduledExecutorService h = hilo;
        if (h == null || h.isShutdown()) {
            return;
        }
        try {
            h.schedule(() -> {
                try {
                    r.run();
                } catch (Exception ex) {
                    log.warn("M2: error: {}", ex.toString(), ex);
                }
            }, ms, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // la app se está deteniendo
        }
    }

    private void configurar() {
        M2Config c = netcfg.getConfig(appId, M2Config.class);
        if (c == null) {
            log.warn("M2: sin network config (apps/tel354.m2/m2): valores por defecto");
        }
        cfg = c == null ? new M2Config.Valores() : c.valores();
        log.info("M2: Tabla 1 {}, DNS {}, captura {} pps (ráfaga {}), cuarentena {} kbps, revisión cada {} s",
                 cfg.tabla1 ? "a cargo de M2" : "desactivada (puente REST)", cfg.dns, cfg.capturaPps,
                 cfg.capturaRafaga, cfg.cuarentenaKbps, cfg.conciliarSeg);
        detenerEscucha();
        BaseDatosM2.Escucha e = new BaseDatosM2.Escucha(cfg.jdbcUrl, cfg.jdbcUsuario, cfg.jdbcClave,
                                                        this::pedirRecarga);
        escucha = e;
        Thread t = hiloDemonio(e, "m2-listen");
        t.start();
        if (periodica != null) {
            periodica.cancel(false);
        }
        periodica = hilo.scheduleWithFixedDelay(this::revisionPeriodica, cfg.conciliarSeg, cfg.conciliarSeg,
                                                TimeUnit.SECONDS);
        pedirRecarga();
    }

    private void detenerEscucha() {
        BaseDatosM2.Escucha e = escucha;
        if (e != null) {
            e.detener();
            escucha = null;
        }
    }

    private void revisionPeriodica() {
        try {
            long ahora = System.currentTimeMillis();
            recientes.values().removeIf(v -> v < ahora);
            pedirRecarga();
        } catch (Exception ex) {
            log.warn("M2: error en la revisión periódica: {}", ex.toString(), ex);
        }
    }

    // ------------------------------------------------------------------ matriz (policy_changed)

    /** Relee la matriz en el hilo de la BD; al terminar, concilia en el hilo de M2. */
    private void pedirRecarga() {
        ExecutorService h = hiloBd;
        if (h == null || h.isShutdown()) {
            return;
        }
        try {
            h.execute(this::recargar);
        } catch (RejectedExecutionException e) {
            // la app se está deteniendo
        }
    }

    private void recargar() {
        M2Config.Valores c = cfg;
        try {
            Politica nueva = BaseDatosM2.cargar(c.jdbcUrl, c.jdbcUsuario, c.jdbcClave);
            Politica previa = politica;
            boolean cambio = !nueva.mismoContenido(previa);
            politica = nueva;
            if (cambio || bdCaida) {
                log.info("M2: matriz {}: {} permisos por rol y {} accesos por curso", previa == null ? "cargada"
                        : "actualizada", nueva.permisos().size(), nueva.filasCurso());
            }
            bdCaida = false;
            if (cambio) {
                enHilo(this::revisarCarreras, 0);
            }
            enHilo(this::conciliarTodo, 0);
        } catch (Exception ex) {
            if (!bdCaida) {
                log.warn("M2: no se pudo leer la matriz ({}); se mantiene la última conocida", ex.toString());
            }
            bdCaida = true;
            enHilo(this::conciliarTodo, 0);                  // los switches nuevos aún reciben la Tabla 1
        }
    }

    // ------------------------------------------------------------------ eventos

    private void alDispositivo(DeviceEvent e) {
        DeviceId d = e.subject().id();
        switch (e.type()) {
            case DEVICE_ADDED:
            case DEVICE_AVAILABILITY_CHANGED:
                if (deviceService.isAvailable(d)) {
                    Estado st = estado(d);
                    st.completaMs = -1;
                    // M7 instala su 600 al detectar el switch: se espera a verla (hasta 60 s)
                    esperarAcceso(d, System.currentTimeMillis());
                }
                break;
            case DEVICE_REMOVED:
                estados.remove(d);
                break;
            default:
                break;
        }
    }

    private void esperarAcceso(DeviceId d, long desde) {
        enHilo(() -> {
            if (uplinkDeM7(d) > 0) {
                programar(d);
            } else if (System.currentTimeMillis() - desde < 60_000 && deviceService.isAvailable(d)) {
                esperarAcceso(d, desde);
            }
        }, 1000);
    }

    private void alConfig(NetworkConfigEvent e) {
        if (e.configClass() == M2Config.class && (e.type() == NetworkConfigEvent.Type.CONFIG_ADDED
                || e.type() == NetworkConfigEvent.Type.CONFIG_UPDATED)) {
            enHilo(this::configurar, 0);
        }
    }

    /** Agrupa los disparos de un mismo switch en una conciliación. */
    private void programar(DeviceId d) {
        if (pendientes.add(d)) {
            enHilo(() -> {
                pendientes.remove(d);
                conciliar(d);
            }, 300);
        }
    }

    private Estado estado(DeviceId d) {
        return estados.computeIfAbsent(d, k -> new Estado());
    }

    // ------------------------------------------------------------------ Tabla 1

    private void conciliarTodo() {
        for (Device dv : deviceService.getAvailableDevices()) {
            programar(dv.id());
        }
    }

    /** Puerto de uplink según la regla 600 de M7, o -1 si el switch no es de acceso (o M7 aún no la puso). */
    private int uplinkDeM7(DeviceId d) {
        ApplicationId m7 = coreService.getAppId(APP_M7);
        if (m7 == null) {
            return -1;
        }
        for (FlowEntry e : flowRuleService.getFlowEntries(d)) {
            if (e.appId() == m7.id() && e.tableId() == Pipeline.T_ADMISION && e.priority() == Pipeline.P_UPLINK) {
                Criterion c = e.selector().getCriterion(Criterion.Type.IN_PORT);
                if (c instanceof PortCriterion) {
                    return (int) ((PortCriterion) c).port().toLong();
                }
            }
        }
        return -1;
    }

    private boolean tienePuente(DeviceId d) {
        ApplicationId p = coreService.getAppId(APP_PUENTE);
        if (p == null) {
            return false;
        }
        for (FlowEntry e : flowRuleService.getFlowEntries(d)) {
            if (e.appId() == p.id() && e.state() != FlowEntry.FlowEntryState.PENDING_REMOVE
                    && e.state() != FlowEntry.FlowEntryState.REMOVED) {
                return true;
            }
        }
        return false;
    }

    private void conciliar(DeviceId d) {
        try {
            conciliarSwitch(d);
        } catch (Exception ex) {
            log.warn("M2: error al conciliar {}: {}", d, ex.toString(), ex);
        }
    }

    private void conciliarSwitch(DeviceId d) {
        if (!deviceService.isAvailable(d) || !mastershipService.isLocalMaster(d)) {
            return;
        }
        int up = uplinkDeM7(d);
        if (up <= 0) {
            Estado previo = estados.get(d);
            if (previo != null && previo.uplink > 0) {
                previo.nota = "sin la regla 600 de M7: se conserva lo instalado";
            }
            return;                                  // no es de acceso (core) o M7 aún no lo programa
        }
        Estado st = estado(d);
        st.uplink = up;
        List<String> faltas = new ArrayList<>();
        Politica p = politica;
        M2Config.Valores c = cfg;
        if (!c.tabla1) {
            st.nota = "Tabla 1 desactivada en la network config (puente REST)";
            return;
        }
        if (tienePuente(d)) {
            st.nota = "puente " + APP_PUENTE + " activo: retirarlo con base_auto.py --solo-tabla1 --borrar";
            return;
        }
        if (p == null) {
            st.nota = "matriz aún no leída" + (bdCaida ? " (PostgreSQL no responde)" : "");
            return;                                  // sin matriz no se toca nada
        }

        // Reglas de la Tabla 1 de M2 que ya están en el almacén (no las 0x02 de sesión)
        Map<Long, FlowEntry> hay = new HashMap<>();
        for (FlowEntry e : flowRuleService.getFlowEntries(d)) {
            int tipo = Cookies.tipo(e.id().value());
            if (e.appId() == appId.id() && (tipo == Cookies.ROL || tipo == Cookies.COMUN)
                    && e.state() != FlowEntry.FlowEntryState.PENDING_REMOVE
                    && e.state() != FlowEntry.FlowEntryState.REMOVED
                    && e.state() != FlowEntry.FlowEntryState.FAILED) {
                hay.put(e.id().value(), e);
            }
        }
        boolean esperaMeters = meters(d, st, hay.values());
        if (esperaMeters) {
            faltas.add("meters pendientes");
        }

        List<ReglaT1> deseadas = Tabla1.reglas(p, c.dns);
        st.deseadas = deseadas.size();
        Map<Long, FlowRule> quiero = new HashMap<>();
        long ahora = System.currentTimeMillis();
        for (ReglaT1 r : deseadas) {
            MeterId m = null;
            if (r.meter != ReglaT1.Meter.NINGUNO) {
                boolean captura = r.meter == ReglaT1.Meter.CAPTURA;
                m = captura ? st.captura : st.cuarentena;
                long pedido = captura ? st.capturaPedidoMs : st.cuarentenaPedidoMs;
                if (m == null || !meterListo(d, m, ahora - pedido)) {
                    // La 55/57 espera a su meter; si ya había una, se conserva mientras tanto.
                    FlowEntry previa = hay.get(Traductor.tabla1(d, r, appId, null).id().value());
                    if (previa != null) {
                        quiero.put(previa.id().value(), previa);
                    }
                    continue;
                }
            }
            FlowRule f = Traductor.tabla1(d, r, appId, m);
            quiero.put(f.id().value(), f);
        }

        FlowRuleOperations.Builder ops = FlowRuleOperations.builder();
        int quitar = 0;
        int poner = 0;
        Set<Long> aceptadas = new HashSet<>();
        for (FlowEntry e : hay.values()) {
            long id = e.id().value();
            FlowRule q = quiero.get(id);
            if (q != null && !Traductor.igual(e, q)) {
                int n = st.reemplazos.merge(id, 1, Integer::sum);
                if (n > 3) {                         // ONOS normaliza la regla: no se entra en un ciclo
                    if (n == 4) {
                        log.warn("M2: {} la regla {} difiere tras 3 reemplazos: se acepta la instalada", d,
                                 Long.toHexString(id));
                    }
                    aceptadas.add(id);
                    continue;
                }
            } else {
                st.reemplazos.remove(id);
            }
            if (q == null || !Traductor.igual(e, q)) {
                ops.remove(e);
                quitar++;
            }
        }
        if (quitar > 0) {
            ops.newStage();
        }
        for (FlowRule q : quiero.values()) {
            FlowEntry e = hay.get(q.id().value());
            if (e == null || (!aceptadas.contains(q.id().value()) && !Traductor.igual(e, q))) {
                ops.add(q);
                poner++;
            }
        }
        st.nota = String.join("; ", faltas);
        if (quitar + poner > 0) {
            st.completaMs = -1;
            log.info("M2: {} Tabla 1: +{} -{} reglas ({} permisos por rol){}", d, poner, quitar,
                     p.permisos().size(), st.nota.isEmpty() ? "" : " [" + st.nota + "]");
            flowRuleService.apply(ops.build(new FlowRuleOperationsContext() {
                @Override
                public void onSuccess(FlowRuleOperations o) {
                    programar(d);                    // se vuelve a mirar: si no falta nada, queda completa
                }

                @Override
                public void onError(FlowRuleOperations o) {
                    log.warn("M2: {} rechazó reglas de la Tabla 1: {}", d, o);
                    enHilo(() -> programar(d), 5000);
                }
            }));
            return;
        }
        if (esperaMeters) {
            enHilo(() -> programar(d), 1000);
            return;
        }
        if (st.completaMs < 0) {
            st.completaMs = System.currentTimeMillis();
            log.info("M2: {} Tabla 1 completa: {} reglas ({} permisos y 8 comunes), meters captura y cuarentena",
                     d, quiero.size(), p.permisos().size());
        }
    }

    // ------------------------------------------------------------------ meters captura y cuarentena

    /**
     * Asegura un meter captura (pps) y uno cuarentena (kbps) por switch de acceso. Los meters de M2 se
     * reconocen por su unidad, así que tras un reinicio de la app se reutilizan los que ya existen.
     *
     * @return true si alguno aún no se puede usar
     */
    private boolean meters(DeviceId d, Estado st, Iterable<FlowEntry> hay) {
        List<Meter> pps = new ArrayList<>();
        List<Meter> kbps = new ArrayList<>();
        for (Meter m : meterService.getMeters(d)) {
            if (appId.equals(m.appId()) && m.state() != MeterState.PENDING_REMOVE) {     // ONOS 2.7: sin REMOVED
                (m.unit() == Meter.Unit.PKTS_PER_SEC ? pps : kbps).add(m);
            }
        }
        // Tras reiniciar la app: preferir el meter que ya usan las reglas 55 y 57 instaladas
        for (FlowEntry e : hay) {
            Instructions.MeterInstruction mi = e.treatment().metered();
            if (mi != null && e.priority() == Pipeline.P_CAPTURA && st.captura == null) {
                st.captura = mi.meterId();
            } else if (mi != null && e.priority() == Pipeline.P_CUARENTENA && st.cuarentena == null) {
                st.cuarentena = mi.meterId();
            }
        }
        long ahora = System.currentTimeMillis();
        boolean espera = false;
        // captura
        if (!contiene(pps, st.captura)) {
            if (st.captura != null && ahora - st.capturaPedidoMs < 10_000) {
                espera = true;                       // recién pedido: aún no aparece en el almacén
            } else if (!pps.isEmpty()) {
                st.captura = pps.get(0).id();
            } else {
                st.captura = pedirMeter(d, true);
                st.capturaPedidoMs = ahora;
                espera = true;
            }
        }
        // cuarentena
        if (!contiene(kbps, st.cuarentena)) {
            if (st.cuarentena != null && ahora - st.cuarentenaPedidoMs < 10_000) {
                espera = true;
            } else if (!kbps.isEmpty()) {
                st.cuarentena = kbps.get(0).id();
            } else {
                st.cuarentena = pedirMeter(d, false);
                st.cuarentenaPedidoMs = ahora;
                espera = true;
            }
        }
        // Meters de M2 sobrantes (duplicados de un reinicio): se retiran
        for (Meter m : pps) {
            if (!m.id().equals(st.captura)) {
                log.info("M2: {} meter {} sobrante: se retira", d, m.id());
                retirarMeter(m);
            }
        }
        for (Meter m : kbps) {
            if (!m.id().equals(st.cuarentena)) {
                log.info("M2: {} meter {} sobrante: se retira", d, m.id());
                retirarMeter(m);
            }
        }
        return espera;
    }

    private static boolean contiene(List<Meter> l, MeterId id) {
        if (id == null) {
            return false;
        }
        for (Meter m : l) {
            if (m.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Un meter se usa si ONOS ya lo ve ADDED o si se pidió hace 1 s: el Meter-Mod sale antes que el
     * Flow-Mod por el mismo canal OpenFlow (mismo criterio que tel354.base).
     */
    private boolean meterListo(DeviceId d, MeterId id, long desdePedidoMs) {
        for (Meter m : meterService.getMeters(d)) {
            if (m.id().equals(id)) {
                return m.state() == MeterState.ADDED || desdePedidoMs >= ESPERA_METER_MS;
            }
        }
        return false;
    }

    private MeterRequest.Builder solicitud(DeviceId d, boolean captura) {
        M2Config.Valores c = cfg;
        Band banda = captura
                ? DefaultBand.builder().ofType(Band.Type.DROP).withRate(c.capturaPps).burstSize(c.capturaRafaga)
                        .build()
                : DefaultBand.builder().ofType(Band.Type.DROP).withRate(c.cuarentenaKbps)
                        .burstSize(c.cuarentenaRafaga).build();
        return DefaultMeterRequest.builder().forDevice(d).fromApp(appId)
                .withUnit(captura ? Meter.Unit.PKTS_PER_SEC : Meter.Unit.KB_PER_SEC).burst()
                .withBands(Collections.singletonList(banda));
    }

    private MeterId pedirMeter(DeviceId d, boolean captura) {
        return meterService.submit(solicitud(d, captura).add()).id();
    }

    private void retirarMeter(Meter m) {
        boolean captura = m.unit() == Meter.Unit.PKTS_PER_SEC;
        meterService.withdraw(solicitud(m.deviceId(), captura).remove(), (MeterCellId) m.id());
    }

    // ------------------------------------------------------------------ slow-path de la regla 55

    /** Solo encola: el hilo de paquetes de ONOS no se bloquea. */
    private class Procesador implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }
            InboundPacket pkt = context.inPacket();
            Ethernet eth = pkt.parsed();
            if (eth == null || eth.getEtherType() != Ethernet.TYPE_IPV4 || eth.getVlanID() == Ethernet.VLAN_UNTAGGED) {
                return;                              // IPv4 sin VLAN es de M1 (505); ARP de M1 (800)
            }
            ConnectPoint cp = pkt.receivedFrom();
            Estado st = estados.get(cp.deviceId());
            if (st == null || st.uplink <= 0 || cp.port().toLong() == st.uplink) {
                return;                              // no es un puerto de host de un switch de acceso
            }
            context.block();                         // el Packet-In de la 55 es de M2
            enHilo(() -> atender(cp, eth), 0);
        }
    }

    private void atender(ConnectPoint cp, Ethernet eth) {
        IPv4 ip = (IPv4) eth.getPayload();
        Ip4Address origen = Ip4Address.valueOf(ip.getSourceAddress());
        Ip4Address destino = Ip4Address.valueOf(ip.getDestinationAddress());
        SesionInfo s = sessionService.getSessionByIp(origen);
        if (s == null || s.estado() != EstadoSesion.ACTIVA || !cp.deviceId().equals(s.dispositivo())
                || !cp.port().equals(s.puerto())) {
            return;                                  // no corresponde a una sesión válida (la IP falsa es de M1)
        }
        int proto = ip.getProtocol() & 0xff;
        int puerto = -1;
        if (ip.getPayload() instanceof TCP) {
            puerto = ((TCP) ip.getPayload()).getDestinationPort();
        } else if (ip.getPayload() instanceof UDP) {
            puerto = ((UDP) ip.getPayload()).getDestinationPort();
        }
        String clave = s.id() + "|" + destino + "|" + proto + "|" + puerto;
        long ahora = System.currentTimeMillis();
        Long vence = recientes.get(clave);
        if (vence != null && vence > ahora) {
            return;                                  // la misma cuádrupla ya se atendió (su DROP está en camino)
        }
        Politica p = politica;
        Politica.Decision dec = p == null ? Politica.Decision.NINGUNA
                : p.decidir(s.octeto(), s.usuarioId(), destino.toInt(), proto, puerto);
        TrafficSelector sel = Traductor.cuadrupla(Ip4Prefix.valueOf(origen, 32), Ip4Prefix.valueOf(destino, 32),
                                                  proto, puerto);
        if (dec != Politica.Decision.NINGUNA) {
            // Carrera con un cambio de política: el permiso aún no está en el switch
            recientes.put(clave, ahora + REENVIO_PERMITIDO_MS);
            carreras.incrementAndGet();
            if (dec == Politica.Decision.ROL) {
                programar(cp.deviceId());            // la conciliación instala la regla 100 que falta
            } else {
                instalarCurso(s, cp.deviceId(), sel);
            }
            reenviar(cp, eth);
            log.info("M2: {} -> {} proto {} puerto {} permitido por la matriz ({}): se reinstala el permiso y "
                     + "se reenvía", s, destino, proto, puerto, dec);
            return;
        }
        recientes.put(clave, ahora + Pipeline.HARD_DENEGADO_SEG * 1000L);
        int indice = contador.getAndIncrement() % MAX_DROP;
        flowRuleService.applyFlowRules(Traductor.drop150(cp.deviceId(), appId, s.id(), indice, sel));
        denegaciones.incrementAndGet();
        AuthorizationEvent e = new AuthorizationEvent(s, destino, proto, puerto, cp.deviceId());
        // Marca para medir_r3.py y para M6 (lee el log de ONOS): "M2: AuthorizationDenied ..."
        log.info("M2: AuthorizationDenied sesion {} [{} {}] -> {} proto {}{} en {}/{}", s.id(), s.usuario(), s.rol(),
                 destino, proto, puerto >= 0 ? " puerto " + puerto : "", cp.deviceId(), cp.port());
        eventDispatcher.post(e);
    }

    /** Packet-Out del paquete permitido: sin la VLAN (como la Tabla 2) por el uplink, hacia el core. */
    private void reenviar(ConnectPoint cp, Ethernet eth) {
        Estado st = estados.get(cp.deviceId());
        if (st == null || st.uplink <= 0) {
            return;
        }
        Ethernet copia = (Ethernet) eth.duplicate();
        copia.setVlanID(Ethernet.VLAN_UNTAGGED);
        packetService.emit(new DefaultOutboundPacket(cp.deviceId(),
                DefaultTrafficTreatment.builder().setOutput(PortNumber.portNumber(st.uplink)).build(),
                ByteBuffer.wrap(copia.serialize())));
    }

    /** Regla 110 de carrera (inscripción nueva con la sesión ya activa): una por cuádrupla y sesión. */
    private void instalarCurso(SesionInfo s, DeviceId d, TrafficSelector sel) {
        Set<Integer> usados = new HashSet<>();
        for (FlowEntry e : flowRuleService.getFlowEntries(d)) {
            long c = e.id().value();
            if (e.appId() == appId.id() && Cookies.tipo(c) == Cookies.SESION && Cookies.id(c) == s.id()
                    && Cookies.indice(c) >= PRIMER_110) {
                if (e.priority() == Pipeline.P_CURSO && e.selector().equals(sel)) {
                    return;                          // ya está
                }
                usados.add(Cookies.indice(c));
            }
        }
        for (int i = PRIMER_110; i <= 255; i++) {
            if (!usados.contains(i)) {
                flowRuleService.applyFlowRules(Traductor.curso110(d, appId, s.id(), i, sel));
                return;
            }
        }
        log.warn("M2: {} sin índices libres para otra 110 de carrera", s);
    }

    /**
     * Tras un cambio de la matriz: las 110 de carrera que ya no corresponden (inscripción retirada o
     * sesión cerrada) se retiran; las vigentes se quedan.
     */
    private void revisarCarreras() {
        Politica p = politica;
        if (p == null) {
            return;
        }
        List<FlowRule> quitar = new ArrayList<>();
        for (FlowEntry e : flowRuleService.getFlowEntriesById(appId)) {
            long c = e.id().value();
            if (Cookies.tipo(c) != Cookies.SESION || Cookies.indice(c) < PRIMER_110
                    || !mastershipService.isLocalMaster(e.deviceId())) {
                continue;
            }
            SesionInfo s = sessionService.getSession(Cookies.id(c));
            if (s == null || s.estado() != EstadoSesion.ACTIVA || !permitidoPorCurso(p, s, e.selector())) {
                quitar.add(e);
            }
        }
        if (!quitar.isEmpty()) {
            flowRuleService.removeFlowRules(quitar.toArray(new FlowRule[0]));
            log.info("M2: {} reglas 110 de carrera retiradas tras el cambio de política", quitar.size());
        }
    }

    private static boolean permitidoPorCurso(Politica p, SesionInfo s, TrafficSelector sel) {
        Criterion dst = sel.getCriterion(Criterion.Type.IPV4_DST);
        Criterion pr = sel.getCriterion(Criterion.Type.IP_PROTO);
        if (!(dst instanceof org.onosproject.net.flow.criteria.IPCriterion)
                || !(pr instanceof org.onosproject.net.flow.criteria.IPProtocolCriterion)) {
            return false;
        }
        int ip = ((org.onosproject.net.flow.criteria.IPCriterion) dst).ip().address().getIp4Address().toInt();
        int proto = ((org.onosproject.net.flow.criteria.IPProtocolCriterion) pr).protocol();
        Criterion l4 = sel.getCriterion(proto == Politica.UDP ? Criterion.Type.UDP_DST : Criterion.Type.TCP_DST);
        int puerto = -1;
        if (l4 instanceof org.onosproject.net.flow.criteria.TcpPortCriterion) {
            puerto = ((org.onosproject.net.flow.criteria.TcpPortCriterion) l4).tcpPort().toInt();
        } else if (l4 instanceof org.onosproject.net.flow.criteria.UdpPortCriterion) {
            puerto = ((org.onosproject.net.flow.criteria.UdpPortCriterion) l4).udpPort().toInt();
        }
        return p.decidir(s.octeto(), s.usuarioId(), ip, proto, puerto) == Politica.Decision.CURSO;
    }

    /** Al cerrarse la sesión, sus DROP 150 y 110 de carrera no deben afectar a quien reciba la misma IP. */
    private void retirarDeSesion(long sesionId) {
        List<FlowRule> l = new ArrayList<>();
        for (FlowEntry e : flowRuleService.getFlowEntriesById(appId)) {
            long c = e.id().value();
            if (Cookies.tipo(c) == Cookies.SESION && Cookies.id(c) == sesionId) {
                l.add(e);
            }
        }
        recientes.keySet().removeIf(k -> k.startsWith(sesionId + "|"));
        if (!l.isEmpty()) {
            flowRuleService.removeFlowRules(l.toArray(new FlowRule[0]));
            log.info("M2: sesión {} cerrada: {} reglas de M2 retiradas", sesionId, l.size());
        }
    }

    // ------------------------------------------------------------------ consola

    /** Líneas de tel354:m2; con {@code reglas}, también la Tabla 1 deseada. */
    public List<String> resumen(boolean reglas) {
        List<String> l = new ArrayList<>();
        Politica p = politica;
        BaseDatosM2.Escucha e = escucha;
        l.add(String.format("Matriz: %s · LISTEN %s: %s · denegaciones %d · carreras %d",
                            p == null ? "sin leer" : p.permisos().size() + " permisos por rol, " + p.filasCurso()
                                    + " accesos por curso", BaseDatosM2.CANAL,
                            e != null && e.conectado() ? "conectado" : "sin conexión", denegaciones.get(),
                            carreras.get()));
        l.add(String.format("%-22s %6s %10s %8s %10s  %s", "switch de acceso", "uplink", "Tabla 1", "DROP 150",
                            "110 carrera", "pendiente"));
        Map<String, Estado> orden = new TreeMap<>();
        estados.forEach((d, st) -> orden.put(d.toString(), st));
        for (Map.Entry<String, Estado> x : orden.entrySet()) {
            DeviceId d = DeviceId.deviceId(x.getKey());
            Estado st = x.getValue();
            int t1 = 0;
            int drops = 0;
            int cursos = 0;
            for (FlowEntry f : flowRuleService.getFlowEntries(d)) {
                if (f.appId() != appId.id() || f.state() != FlowEntry.FlowEntryState.ADDED) {
                    continue;
                }
                int tipo = Cookies.tipo(f.id().value());
                if (tipo == Cookies.ROL || tipo == Cookies.COMUN) {
                    t1++;
                } else if (f.priority() == Pipeline.P_DENEGADO) {
                    drops++;
                } else {
                    cursos++;
                }
            }
            l.add(String.format("%-22s %6s %10s %8d %10d  %s", x.getKey(), st.uplink, t1 + "/" + st.deseadas, drops,
                                cursos, st.nota));
        }
        if (reglas && p != null) {
            for (ReglaT1 r : Tabla1.reglas(p, cfg.dns)) {
                l.add("    " + r);
            }
        }
        return l;
    }
}
