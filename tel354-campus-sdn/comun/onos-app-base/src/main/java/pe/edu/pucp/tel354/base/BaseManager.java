package pe.edu.pucp.tel354.base;

import org.onosproject.app.ApplicationService;
import org.onosproject.app.ApplicationState;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.mastership.MastershipService;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Link;
import org.onosproject.net.Port;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.config.basics.SubjectFactories;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleOperations;
import org.onosproject.net.flow.FlowRuleOperationsContext;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.PortCriterion;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flow.instructions.Instructions;
import org.onosproject.net.link.LinkEvent;
import org.onosproject.net.link.LinkListener;
import org.onosproject.net.link.LinkService;
import org.onosproject.net.meter.Band;
import org.onosproject.net.meter.DefaultBand;
import org.onosproject.net.meter.DefaultMeterRequest;
import org.onosproject.net.meter.Meter;
import org.onosproject.net.meter.MeterCellId;
import org.onosproject.net.meter.MeterId;
import org.onosproject.net.meter.MeterRequest;
import org.onosproject.net.meter.MeterService;
import org.onosproject.net.meter.MeterState;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.edu.pucp.tel354.api.Cookies;
import pe.edu.pucp.tel354.api.Pipeline;
import pe.edu.pucp.tel354.base.nucleo.Regimen;
import pe.edu.pucp.tel354.base.nucleo.Regla;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * tel354.base: M7 (régimen base) y M8 (caminos fijos) instalados por el controlador al detectar
 * cada switch, en lugar de base_auto.py.
 *
 * Conciliación: para cada switch se calcula el conjunto deseado de reglas (Regimen) a partir de su
 * rol (switch_acceso o el core de la network config) y de sus puertos y enlaces; se compara con
 * las reglas de M7 y M8 que ya están en el almacén de ONOS y solo se envía la diferencia. Es
 * idempotente: una reconexión o un reinicio de la app no duplican nada. Se dispara con los
 * eventos de dispositivo y de enlace y, como respaldo, cada conciliarSeg (30 s).
 *
 * Una regla solo se retira con información positiva (el puerto ya no existe, el switch salió de
 * switch_acceso, el pabellón cambió). Si falta información de topología (LLDP/BDDP aún no
 * descubre un enlace), se usa lo último conocido; tras un reinicio de la app, lo último conocido
 * se recupera de las reglas que ya están en el almacén de ONOS.
 *
 * Los meters ctl_P (uno por puerto de host) se crean con MeterService; la regla 800 de un puerto
 * se instala después de su meter (los meters de ONOS 2.7 son asíncronos: ver ESPERA_METER_MS).
 */
@Component(immediate = true, service = BaseManager.class)
public class BaseManager {

    public static final String APP = "tel354.base";
    public static final String APP_M7 = "tel354.m7";
    public static final String APP_M8 = "tel354.m8";
    private static final String HILO = "base-conciliacion";
    /**
     * Un meter se usa si ONOS ya lo ve ADDED o si se pidió hace 1 s: el Meter-Mod sale antes que el
     * Flow-Mod por el mismo canal OpenFlow, y ONOS confirma el estado ADDED recién con sus
     * estadísticas de meters, que pueden tardar varios segundos.
     */
    private static final long ESPERA_METER_MS = 1000;
    /** Tiempo durante el que se sondea cada segundo la confirmación (ADDED) de un switch recién conectado. */
    private static final long ESPERA_ADDED_MS = 60_000;

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ApplicationService applicationService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected LinkService linkService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected MeterService meterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected MastershipService mastershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry netcfg;

    /** Lo que la app sabe de cada switch; se conserva ante pérdidas transitorias de LLDP/BDDP. */
    static final class Estado {
        volatile String rol = "?";
        volatile String nota = "";
        volatile long desdeMs = System.currentTimeMillis();
        volatile long convergenciaMs = -1;
        /** Último lote de Flow-Mods confirmado por el switch (barrier), o -1 si no hubo. */
        volatile long loteMs = -1;
        volatile int uplink = -1;
        volatile int puertoServidores = -1;
        volatile List<Integer> hosts = Collections.emptyList();
        volatile List<Regla> deseadas = Collections.emptyList();
        volatile int metersListos;
        /** Core: pabellón → puerto hacia su switch de acceso. */
        final Map<Integer, Integer> pabellonPuerto = new ConcurrentHashMap<>();
        /** Acceso: puerto de host → su meter ctl_P, y cuándo se pidió. */
        final Map<Integer, MeterId> meters = new ConcurrentHashMap<>();
        final Map<Integer, Long> meterPedido = new ConcurrentHashMap<>();
        /** Reemplazos seguidos de una misma regla: corta un ciclo si ONOS normaliza la regla. */
        final Map<Long, Integer> reemplazos = new ConcurrentHashMap<>();
    }

    private ApplicationId appBase;
    private ApplicationId appM7;
    private ApplicationId appM8;
    private volatile BaseConfig.Valores cfg = new BaseConfig.Valores();
    /** dpid → pabellón (switch_acceso); null hasta la primera lectura de la BD. */
    private volatile Map<String, Integer> pabellones;
    private volatile boolean bdCaida;
    private ScheduledExecutorService hilo;
    private ScheduledFuture<?> periodica;
    private final Set<DeviceId> pendientes = ConcurrentHashMap.newKeySet();
    private final Map<DeviceId, Estado> estados = new ConcurrentHashMap<>();

    private final ConfigFactory<ApplicationId, BaseConfig> fabrica =
            new ConfigFactory<ApplicationId, BaseConfig>(SubjectFactories.APP_SUBJECT_FACTORY,
                                                         BaseConfig.class, BaseConfig.CLAVE) {
                @Override
                public BaseConfig createConfig() {
                    return new BaseConfig();
                }
            };

    private final DeviceListener oyenteDispositivos = this::alDispositivo;
    private final LinkListener oyenteEnlaces = this::alEnlace;
    private final NetworkConfigListener oyenteConfig = this::alConfig;

    // ------------------------------------------------------------------ ciclo de vida

    @Activate
    protected void activate() {
        appBase = coreService.registerApplication(APP);
        appM7 = coreService.registerApplication(APP_M7);
        appM8 = coreService.registerApplication(APP_M8);
        hilo = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, HILO);
            t.setDaemon(true);
            return t;
        });
        netcfg.registerConfigFactory(fabrica);
        netcfg.addListener(oyenteConfig);
        deviceService.addListener(oyenteDispositivos);
        linkService.addListener(oyenteEnlaces);
        enHilo(this::configurar, 0);
        log.info("M7+M8 iniciados (appId M7 {}, M8 {})", appM7.id(), appM8.id());
    }

    @Deactivate
    protected void deactivate() {
        boolean appDesactivada = applicationService.getState(appBase) != ApplicationState.ACTIVE;
        deviceService.removeListener(oyenteDispositivos);
        linkService.removeListener(oyenteEnlaces);
        netcfg.removeListener(oyenteConfig);
        hilo.shutdownNow();
        if (appDesactivada) {
            // Desactivar la app retira el régimen base. M1 declara tel354.base en onos.app.requires,
            // así que ONOS ya desactivó antes a M1 y a M5 (sus reglas 525 y 505 usan los meters ctl_P).
            flowRuleService.removeFlowRulesById(appM7);
            flowRuleService.removeFlowRulesById(appM8);
            int n = 0;
            for (Meter m : meterService.getAllMeters()) {
                if (appM7.equals(m.appId())) {
                    retirarMeter(m.deviceId(), m.id());
                    n++;
                }
            }
            log.info("M7+M8: reglas y {} meters retirados", n);
        }
        estados.clear();
        netcfg.unregisterConfigFactory(fabrica);
        log.info("M7+M8 detenidos ({})", appDesactivada ? "app desactivada" : "instancia apagada");
    }

    /** Network config de la app; el banco de pruebas la sustituye. */
    protected BaseConfig.Valores cargarConfig() {
        BaseConfig c = netcfg.getConfig(appBase, BaseConfig.class);
        if (c == null) {
            log.warn("M7: sin network config (apps/tel354.base/base): valores por defecto");
            return new BaseConfig.Valores();
        }
        return c.valores();
    }

    /** dpid → pabellón de switch_acceso; el banco de pruebas lo sustituye. */
    protected Map<String, Integer> cargarInventario() throws Exception {
        return Inventario.pabellones(cfg.jdbcUrl, cfg.jdbcUsuario, cfg.jdbcClave);
    }

    /** Ejecuta en el hilo de la app; si la app se está deteniendo, no hace nada. */
    private void enHilo(Runnable r, long ms) {
        ScheduledExecutorService h = hilo;
        if (h == null || h.isShutdown()) {
            return;
        }
        try {
            h.schedule(r, ms, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // el hilo se cerró entre la comprobación y la entrega
        }
    }

    private void configurar() {
        cfg = cargarConfig();
        log.info("M7: core {}, {} destinos fijos, ctl_P {} pps (ráfaga {}), conciliación cada {} s",
                 cfg.core, cfg.destinos.size(), cfg.ctlPps, cfg.ctlRafaga, cfg.conciliarSeg);
        if (periodica != null) {
            periodica.cancel(false);
        }
        periodica = hilo.scheduleWithFixedDelay(this::conciliarTodo, 0, cfg.conciliarSeg, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ eventos

    private void alDispositivo(DeviceEvent e) {
        DeviceId d = e.subject().id();
        switch (e.type()) {
            case DEVICE_ADDED:
            case DEVICE_AVAILABILITY_CHANGED:
                if (deviceService.isAvailable(d)) {
                    Estado st = estado(d);
                    st.desdeMs = e.time();
                    st.convergenciaMs = -1;
                    st.loteMs = -1;
                    if (e.type() == DeviceEvent.Type.DEVICE_ADDED) {
                        enHilo(this::leerInventario, 0);      // un switch nuevo no espera los 30 s
                    }
                    programar(d);
                }
                break;
            case PORT_ADDED:
            case PORT_REMOVED:
                programar(d);
                break;
            case DEVICE_REMOVED:
                estados.remove(d);
                break;
            default:
                break;
        }
    }

    private void alEnlace(LinkEvent e) {
        programar(e.subject().src().deviceId());
        programar(e.subject().dst().deviceId());
    }

    private void alConfig(NetworkConfigEvent e) {
        if (e.configClass() == BaseConfig.class && (e.type() == NetworkConfigEvent.Type.CONFIG_ADDED
                || e.type() == NetworkConfigEvent.Type.CONFIG_UPDATED)) {
            enHilo(this::configurar, 0);
        }
    }

    /** Agrupa los eventos de un switch que llegan juntos (p. ej., sus puertos) en una conciliación. */
    private void programar(DeviceId d) {
        ScheduledExecutorService h = hilo;
        if (h != null && !h.isShutdown() && pendientes.add(d)) {
            try {
                h.schedule(() -> {
                    pendientes.remove(d);
                    conciliar(d);
                }, 300, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                pendientes.remove(d);
            }
        }
    }

    private Estado estado(DeviceId d) {
        return estados.computeIfAbsent(d, k -> new Estado());
    }

    // ------------------------------------------------------------------ conciliación

    private void conciliarTodo() {
        try {                                       // una excepción aquí cancelaría la tarea periódica
            leerInventario();
            for (Device dv : deviceService.getAvailableDevices()) {
                programar(dv.id());
            }
        } catch (Exception ex) {
            log.warn("M7: error en la conciliación periódica: {}", ex.toString(), ex);
        }
    }

    /** Relee switch_acceso; si falla, se conserva lo último conocido. */
    private void leerInventario() {
        try {
            Map<String, Integer> m = cargarInventario();
            if (!m.equals(pabellones) || bdCaida) {
                log.info("M7: switch_acceso con {} switches de acceso: {}", m.size(), new TreeMap<>(m));
            }
            pabellones = m;
            bdCaida = false;
        } catch (Exception ex) {
            if (!bdCaida) {
                log.warn("M7: no se pudo leer switch_acceso ({}); se mantiene lo último conocido", ex.toString());
            }
            bdCaida = true;
        }
    }

    private void conciliar(DeviceId d) {
        try {
            conciliarSwitch(d);
        } catch (Exception ex) {
            log.warn("M7: error al conciliar {}: {}", d, ex.toString(), ex);
        }
    }

    private void conciliarSwitch(DeviceId d) {
        if (!deviceService.isAvailable(d) || !mastershipService.isLocalMaster(d)) {
            return;
        }
        Estado st = estado(d);
        DeviceId core = DeviceId.deviceId(cfg.core);

        // Reglas de M7 y M8 que ya están en el almacén (las que ONOS está quitando no cuentan)
        Map<Long, FlowEntry> hay = new HashMap<>();
        for (FlowEntry e : flowRuleService.getFlowEntries(d)) {
            if ((e.appId() == appM7.id() || e.appId() == appM8.id())
                    && e.state() != FlowEntry.FlowEntryState.PENDING_REMOVE
                    && e.state() != FlowEntry.FlowEntryState.REMOVED
                    && e.state() != FlowEntry.FlowEntryState.FAILED) {
                hay.put(e.id().value(), e);
            }
        }
        recuperar(st, d.equals(core), hay.values());

        String modulo;
        List<Regla> deseadas;
        List<String> faltas = new ArrayList<>();
        if (d.equals(core)) {
            modulo = "M8";
            st.rol = "core";
            deseadas = Regimen.core(pabellonesDelCore(st, core, faltas), destinos(st, core, faltas));
        } else {
            modulo = "M7";
            Map<String, Integer> inv = pabellones;
            if (inv == null) {
                st.rol = "?";
                st.nota = "switch_acceso aún no leída";
                return;                              // sin inventario no se toca nada
            }
            Integer n = inv.get(d.toString());
            if (n == null) {
                st.rol = "sin rol";
                faltas.add("no está en switch_acceso");
                st.hosts = Collections.emptyList();
                deseadas = Collections.emptyList();  // se retira lo que hubiera de M7 y M8
            } else {
                st.rol = "acceso " + n;
                int up = uplink(st, d, core);
                if (up <= 0) {
                    faltas.add("sin enlace con el core");
                }
                st.hosts = up > 0 ? puertosHost(d, up) : Collections.<Integer>emptyList();
                deseadas = Regimen.acceso(up, st.hosts);
            }
        }
        st.deseadas = deseadas;

        // Meters ctl_P de los puertos de host
        Map<Integer, MeterId> listos = new HashMap<>();
        boolean gestionaMeters = st.rol.startsWith("acceso") ? st.uplink > 0 : st.rol.equals("sin rol");
        boolean esperaMeters = cfg.ctlPps > 0 && gestionaMeters && meters(d, st, hay.values(), listos);
        if (esperaMeters) {
            faltas.add("meters pendientes");
        }

        Map<Long, FlowRule> quiero = new HashMap<>();
        for (Regla r : deseadas) {
            MeterId m = null;
            if (r.meterDePuerto != null && cfg.ctlPps > 0) {
                m = listos.get(r.meterDePuerto);
                if (m == null) {
                    // La 800 espera a su meter; si ya había una, se conserva mientras tanto.
                    FlowRule sinMeter = Traductor.flowRule(d, r, appM7, null);
                    FlowEntry previa = hay.get(sinMeter.id().value());
                    if (previa != null) {
                        quiero.put(previa.id().value(), previa);
                    }
                    continue;
                }
            }
            FlowRule f = Traductor.flowRule(d, r, r.modulo == Regla.Modulo.M7 ? appM7 : appM8, m);
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
                if (n > 3) {
                    if (n == 4) {
                        log.warn("{}: {} la regla {} difiere tras 3 reemplazos: se acepta la instalada", modulo, d,
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
            log.info("{}: {} ({}): +{} -{} reglas{}", modulo, d, st.rol, poner, quitar,
                     st.nota.isEmpty() ? "" : " [" + st.nota + "]");
            flowRuleService.apply(ops.build(new FlowRuleOperationsContext() {
                @Override
                public void onSuccess(FlowRuleOperations o) {
                    st.loteMs = System.currentTimeMillis();
                    programar(d);                    // se vuelve a mirar: si no falta nada, queda completo
                }

                @Override
                public void onError(FlowRuleOperations o) {
                    log.warn("{}: {} rechazó reglas del régimen base: {}", modulo, d, o);
                    enHilo(() -> programar(d), 5000);
                }
            }));
            return;
        }
        if (esperaMeters) {
            enHilo(() -> programar(d), 1000);
            return;
        }
        if (faltas.isEmpty() && st.convergenciaMs < 0) {
            // Completo cuando ONOS confirma todas las reglas en el switch (ADDED, por estadísticas)
            for (Long id : quiero.keySet()) {
                FlowEntry e = hay.get(id);
                if (e == null || e.state() != FlowEntry.FlowEntryState.ADDED) {
                    st.nota = "esperando la confirmación de ONOS";
                    if (System.currentTimeMillis() - st.desdeMs < ESPERA_ADDED_MS) {
                        enHilo(() -> programar(d), 1000);    // después lo retoma la conciliación periódica
                    }
                    return;
                }
            }
            long ahora = System.currentTimeMillis();
            st.convergenciaMs = Math.max(0, ahora - st.desdeMs);
            log.info("{}: {} ({}) régimen base completo: {} reglas, {} meters; Flow-Mods confirmados a los {} ms "
                     + "y reglas ADDED a los {} ms de la conexión", modulo, d, st.rol, quiero.size(), st.metersListos,
                     st.loteMs > 0 ? Math.max(0, st.loteMs - st.desdeMs) : "-", st.convergenciaMs);
        }
    }

    // ------------------------------------------------------------------ topología

    /**
     * Tras un reinicio de la app, lo último conocido de un switch (uplink, puerto de cada pabellón y
     * puerto de los servidores) se toma de sus propias reglas. Así, si en ese momento falta un enlace
     * por descubrir, no se retira nada. La información de LLDP/BDDP, cuando llega, prevalece.
     */
    private void recuperar(Estado st, boolean esCore, Iterable<FlowEntry> hay) {
        for (FlowEntry e : hay) {
            long id = Cookies.id(e.id().value());
            int prioridad = (int) (id >>> 16);
            int grupo = (int) (id & 0xffff);
            int indice = Cookies.indice(e.id().value());
            if (!esCore) {
                if (st.uplink <= 0 && e.appId() == appM7.id() && prioridad == Pipeline.P_UPLINK) {
                    Criterion c = e.selector().getCriterion(Criterion.Type.IN_PORT);
                    if (c instanceof PortCriterion) {
                        st.uplink = (int) ((PortCriterion) c).port().toLong();
                    }
                }
            } else if (e.appId() == appM8.id() && indice == 0) {
                int salida = salida(e);
                if (salida <= 0) {
                    continue;
                }
                if (prioridad == Regimen.P_CORE_TRANSITO && grupo > 0) {
                    st.pabellonPuerto.putIfAbsent(grupo, salida);
                } else if (prioridad == Regimen.P_CORE_RETORNO && grupo == 0 && st.puertoServidores <= 0) {
                    st.puertoServidores = salida;
                }
            }
        }
    }

    /** Puerto de salida de una regla, o -1 si no tiene (drop, controlador o salto de tabla). */
    private static int salida(FlowRule r) {
        for (Instruction i : r.treatment().allInstructions()) {
            if (i instanceof Instructions.OutputInstruction) {
                long p = ((Instructions.OutputInstruction) i).port().toLong();
                return ((Instructions.OutputInstruction) i).port().isLogical() ? -1 : (int) p;
            }
        }
        return -1;
    }

    /** Puerto del switch de acceso hacia el core; se conserva el último conocido. */
    private int uplink(Estado st, DeviceId d, DeviceId core) {
        for (Link l : linkService.getDeviceEgressLinks(d)) {
            if (l.dst().deviceId().equals(core)) {
                st.uplink = (int) l.src().port().toLong();
                break;
            }
        }
        return st.uplink;
    }

    /** Puertos físicos que no son el uplink ni tienen enlace con otro switch. */
    private List<Integer> puertosHost(DeviceId d, int uplink) {
        Set<Long> enlaces = puertosConEnlace(d);
        List<Integer> h = new ArrayList<>();
        for (Port p : deviceService.getPorts(d)) {
            if (p.number().isLogical()) {
                continue;
            }
            long x = p.number().toLong();
            if (x != uplink && !enlaces.contains(x)) {
                h.add((int) x);
            }
        }
        Collections.sort(h);
        return h;
    }

    private Set<Long> puertosConEnlace(DeviceId d) {
        Set<Long> s = new HashSet<>();
        for (Link l : linkService.getDeviceLinks(d)) {
            if (l.src().deviceId().equals(d)) {
                s.add(l.src().port().toLong());
            }
            if (l.dst().deviceId().equals(d)) {
                s.add(l.dst().port().toLong());
            }
        }
        return s;
    }

    /** Core: pabellón → puerto, con los enlaces hacia los switches de switch_acceso. */
    private Map<Integer, Integer> pabellonesDelCore(Estado st, DeviceId core, List<String> faltas) {
        Map<String, Integer> inv = pabellones;
        if (inv == null) {
            faltas.add("switch_acceso aún no leída");
            return new TreeMap<>(st.pabellonPuerto);
        }
        for (Link l : linkService.getDeviceEgressLinks(core)) {
            Integer n = inv.get(l.dst().deviceId().toString());
            if (n != null) {
                st.pabellonPuerto.put(n, (int) l.src().port().toLong());
            }
        }
        st.pabellonPuerto.keySet().retainAll(inv.values());     // pabellones dados de baja
        for (Map.Entry<String, Integer> e : inv.entrySet()) {
            if (deviceService.isAvailable(DeviceId.deviceId(e.getKey()))
                    && !st.pabellonPuerto.containsKey(e.getValue())) {
                faltas.add("sin enlace con el pabellón " + e.getValue());
            }
        }
        return new TreeMap<>(st.pabellonPuerto);
    }

    /** Destinos fijos del core; puerto 0 = el único puerto del core sin enlace. */
    private List<Regimen.Destino> destinos(Estado st, DeviceId core, List<String> faltas) {
        List<Regimen.Destino> r = new ArrayList<>();
        for (BaseConfig.DestinoCfg c : cfg.destinos) {
            int p = c.puerto > 0 ? c.puerto : puertoLibre(st, core);
            if (p <= 0) {
                faltas.add("puerto de " + c.prefijo + " sin resolver (fijar \"puerto\" en la network config)");
            }
            r.add(new Regimen.Destino(c.prefijo, c.mac, p));
        }
        return r;
    }

    private int puertoLibre(Estado st, DeviceId core) {
        Set<Long> enlaces = puertosConEnlace(core);
        List<Long> libres = new ArrayList<>();
        for (Port p : deviceService.getPorts(core)) {
            long x = p.number().toLong();
            if (!p.number().isLogical() && !enlaces.contains(x) && !st.pabellonPuerto.containsValue((int) x)) {
                libres.add(x);
            }
        }
        if (libres.size() == 1) {
            st.puertoServidores = libres.get(0).intValue();
        }
        return st.puertoServidores;
    }

    // ------------------------------------------------------------------ meters ctl_P

    /**
     * Asegura un meter ctl_P por puerto de host. Devuelve true si alguno aún no está ADDED.
     * Tras reiniciar la app, la asociación puerto → meter se recupera de las reglas 800 existentes.
     */
    private boolean meters(DeviceId d, Estado st, Iterable<FlowEntry> hay, Map<Integer, MeterId> listos) {
        Map<MeterId, Meter> mios = new HashMap<>();
        for (Meter m : meterService.getMeters(d)) {
            if (appM7.equals(m.appId()) && m.state() != MeterState.PENDING_REMOVE) {   // ONOS 2.7: sin REMOVED
                mios.put(m.id(), m);
            }
        }
        if (st.meters.isEmpty()) {
            for (FlowEntry e : hay) {
                if (e.appId() == appM7.id() && e.priority() == Pipeline.P_ARP) {
                    Criterion c = e.selector().getCriterion(Criterion.Type.IN_PORT);
                    Instructions.MeterInstruction mi = e.treatment().metered();
                    if (c instanceof PortCriterion && mi != null && mios.containsKey(mi.meterId())) {
                        st.meters.put((int) ((PortCriterion) c).port().toLong(), mi.meterId());
                    }
                }
            }
        }
        boolean espera = false;
        long ahora = System.currentTimeMillis();
        for (int p : st.hosts) {
            MeterId id = st.meters.get(p);
            Meter m = id == null ? null : mios.get(id);
            if (m == null) {
                Long pedido = st.meterPedido.get(p);
                if (id != null && pedido != null && ahora - pedido < 10_000) {
                    espera = true;                   // recién pedido: aún no aparece en el almacén
                    continue;
                }
                st.meters.put(p, crearMeter(d));
                st.meterPedido.put(p, ahora);
                espera = true;
            } else if (m.state() == MeterState.ADDED
                    || ahora - st.meterPedido.getOrDefault(p, 0L) >= ESPERA_METER_MS) {
                listos.put(p, id);
            } else {
                espera = true;
            }
        }
        // Puertos que dejaron de ser de host y meters de M7 sin puerto: se retiran
        st.meters.keySet().retainAll(st.hosts);
        st.meterPedido.keySet().retainAll(st.hosts);
        Set<MeterId> usados = new HashSet<>(st.meters.values());
        for (Meter m : mios.values()) {
            if (!usados.contains(m.id())) {
                log.info("M7: {} meter {} sin puerto de host: se retira", d, m.id());
                retirarMeter(d, m.id());
            }
        }
        st.metersListos = listos.size();
        return espera;
    }

    private Band banda() {
        return DefaultBand.builder().ofType(Band.Type.DROP).withRate(cfg.ctlPps).burstSize(cfg.ctlRafaga).build();
    }

    private MeterId crearMeter(DeviceId d) {
        MeterRequest req = DefaultMeterRequest.builder().forDevice(d).fromApp(appM7)
                .withUnit(Meter.Unit.PKTS_PER_SEC).burst().withBands(Collections.singletonList(banda())).add();
        return meterService.submit(req).id();
    }

    private void retirarMeter(DeviceId d, MeterId id) {
        MeterRequest req = DefaultMeterRequest.builder().forDevice(d).fromApp(appM7)
                .withUnit(Meter.Unit.PKTS_PER_SEC).burst().withBands(Collections.singletonList(banda())).remove();
        meterService.withdraw(req, (MeterCellId) id);   // MeterId implementa MeterCellId
    }

    // ------------------------------------------------------------------ consola

    /** Una línea por switch para tel354:base; con {@code reglas}, también el conjunto deseado. */
    public List<String> resumen(boolean reglas) {
        List<String> l = new ArrayList<>();
        l.add(String.format("%-22s %-10s %6s %-12s %8s %7s %9s  %s", "switch", "rol", "uplink", "hosts",
                            "reglas", "meters", "conv (ms)", "pendiente"));
        Map<String, Estado> orden = new TreeMap<>();
        estados.forEach((d, st) -> orden.put(d.toString(), st));
        for (Map.Entry<String, Estado> e : orden.entrySet()) {
            Estado st = e.getValue();
            DeviceId d = DeviceId.deviceId(e.getKey());
            int instaladas = 0;
            for (FlowEntry f : flowRuleService.getFlowEntries(d)) {
                if ((f.appId() == appM7.id() || f.appId() == appM8.id())
                        && f.state() == FlowEntry.FlowEntryState.ADDED) {
                    instaladas++;
                }
            }
            l.add(String.format("%-22s %-10s %6s %-12s %8s %7s %9s  %s", e.getKey(), st.rol,
                                st.uplink > 0 ? st.uplink : (st.rol.equals("core") ? "-" : "?"),
                                st.hosts.toString().replace(" ", ""),
                                instaladas + "/" + st.deseadas.size(),
                                st.rol.startsWith("acceso") ? st.metersListos + "/" + st.hosts.size() : "-",
                                st.convergenciaMs >= 0 ? st.convergenciaMs : "-", st.nota));
            if (reglas) {
                for (Regla r : st.deseadas) {
                    l.add("    " + r);
                }
            }
        }
        return l;
    }
}
