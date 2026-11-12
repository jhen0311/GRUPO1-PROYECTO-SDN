package pe.edu.pucp.tel354.base.banco;

import org.onosproject.app.ApplicationService;
import org.onosproject.app.ApplicationState;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.mastership.MastershipService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Link;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.config.Config;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleOperations;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.instructions.Instructions;
import org.onosproject.net.link.LinkEvent;
import org.onosproject.net.link.LinkListener;
import org.onosproject.net.link.LinkService;
import org.onosproject.net.meter.Meter;
import org.onosproject.net.meter.MeterCellId;
import org.onosproject.net.meter.MeterId;
import org.onosproject.net.meter.MeterRequest;
import org.onosproject.net.meter.MeterService;
import org.onosproject.net.meter.MeterState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ONOS y los switches, simulados para el banco de tel354.base. Reproduce lo que importa de
 * ONOS 2.7.0 para la app:
 *
 *  - Almacén de reglas y de meters separado de lo que el switch tiene de verdad.
 *  - Una regla queda PENDING_ADD y pasa a ADDED con el sondeo de estadísticas de flujos; un meter,
 *    con el de meters, que es más lento (en ONOS, 5 s y 10 s; aquí, 0,5 s y 2,5 s).
 *  - El switch rechaza una regla que usa un meter que aún no tiene, y al borrar un meter borra las
 *    reglas que lo usan (OpenFlow 1.3).
 *  - Si al switch le falta algo que está en el almacén (reinicio), ONOS lo reinstala en el sondeo.
 *  - Los eventos de dispositivo y de enlace llegan en otro hilo.
 */
public class SimOnos implements CoreService, ApplicationService, MastershipService, DeviceService, LinkService,
        FlowRuleService, MeterService, NetworkConfigRegistry {

    static final long SONDEO_FLUJOS_MS = 500;
    static final long SONDEO_METERS_MS = 2500;
    static final long RETARDO_OPENFLOW_MS = 40;

    /** Un switch: lo que ONOS cree (almacén) y lo que tiene instalado. */
    static final class Sw implements Device {
        final DeviceId id;
        volatile boolean disponible = true;
        final Set<Long> puertos = new TreeSet<>();
        final Map<Long, DefaultFlowRule> almacen = new ConcurrentHashMap<>();
        final Map<Long, FlowRule> instaladas = new ConcurrentHashMap<>();
        final Map<MeterId, SimMeter> metersAlmacen = new ConcurrentHashMap<>();
        final Set<MeterId> metersInstalados = ConcurrentHashMap.newKeySet();
        final AtomicInteger siguienteMeter = new AtomicInteger(1);

        Sw(DeviceId id) {
            this.id = id;
        }

        public DeviceId id() {
            return id;
        }
    }

    static final class SimMeter implements Meter {
        final DeviceId d;
        final MeterId id;
        final ApplicationId app;
        volatile MeterState estado = MeterState.PENDING_ADD;

        SimMeter(DeviceId d, MeterId id, ApplicationId app) {
            this.d = d;
            this.id = id;
            this.app = app;
        }

        public MeterId id() { return id; }
        public MeterState state() { return estado; }
        public ApplicationId appId() { return app; }
        public DeviceId deviceId() { return d; }
    }

    private static final class Enlace implements Link {
        final ConnectPoint a;
        final ConnectPoint b;

        Enlace(DeviceId da, long pa, DeviceId db, long pb) {
            a = new ConnectPoint(da, PortNumber.portNumber(pa));
            b = new ConnectPoint(db, PortNumber.portNumber(pb));
        }

        public ConnectPoint src() { return a; }
        public ConnectPoint dst() { return b; }
    }

    final Map<DeviceId, Sw> switches = new ConcurrentHashMap<>();
    private final List<Enlace> enlaces = new CopyOnWriteArrayList<>();
    private final Map<String, ApplicationId> apps = new ConcurrentHashMap<>();
    private final List<DeviceListener> oyentesDisp = new CopyOnWriteArrayList<>();
    private final List<LinkListener> oyentesEnlace = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService onos = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "onos-simulado");
        t.setDaemon(true);
        return t;
    });

    volatile ApplicationState estadoApp = ApplicationState.ACTIVE;
    /** Lo que la app pidió: Flow-Mods de alta y de baja, meters creados y retirados, reglas rechazadas. */
    final AtomicInteger altas = new AtomicInteger();
    final AtomicInteger bajas = new AtomicInteger();
    final AtomicInteger metersCreados = new AtomicInteger();
    final AtomicInteger metersRetirados = new AtomicInteger();
    final AtomicInteger rechazadas = new AtomicInteger();
    /** Reglas que ONOS reinstaló por su cuenta (no la app). */
    final AtomicInteger reinstaladas = new AtomicInteger();

    SimOnos() {
        onos.scheduleWithFixedDelay(this::sondeoFlujos, SONDEO_FLUJOS_MS, SONDEO_FLUJOS_MS, TimeUnit.MILLISECONDS);
        onos.scheduleWithFixedDelay(this::sondeoMeters, SONDEO_METERS_MS, SONDEO_METERS_MS, TimeUnit.MILLISECONDS);
    }

    static DeviceId dpid(String corto) {
        return DeviceId.deviceId("of:" + "0000000000000000".substring(corto.length()) + corto);
    }

    // ------------------------------------------------------------------ lo que hace el operador

    void conectar(String corto, long... puertos) {
        Sw s = new Sw(dpid(corto));
        for (long p : puertos) {
            s.puertos.add(p);
        }
        switches.put(s.id, s);
        evento(new DeviceEvent(DeviceEvent.Type.DEVICE_ADDED, s, null));
    }

    void enlazar(String a, long pa, String b, long pb) {
        for (Enlace l : new Enlace[] {new Enlace(dpid(a), pa, dpid(b), pb), new Enlace(dpid(b), pb, dpid(a), pa)}) {
            enlaces.add(l);
            onos.execute(() -> oyentesEnlace.forEach(o -> o.event(new LinkEvent(LinkEvent.Type.LINK_ADDED, l))));
        }
    }

    /** El descubrimiento pierde el enlace (con aviso) o ONOS aún no lo ve tras un reinicio (sin aviso). */
    void desenlazar(String a, String b, boolean avisar) {
        for (Enlace l : enlaces) {
            boolean es = (l.a.deviceId().equals(dpid(a)) && l.b.deviceId().equals(dpid(b)))
                    || (l.a.deviceId().equals(dpid(b)) && l.b.deviceId().equals(dpid(a)));
            if (es) {
                enlaces.remove(l);
                if (avisar) {
                    onos.execute(() -> oyentesEnlace.forEach(o -> o.event(new LinkEvent(LinkEvent.Type.LINK_REMOVED, l))));
                }
            }
        }
    }

    void agregarPuerto(String corto, long p) {
        Sw s = switches.get(dpid(corto));
        s.puertos.add(p);
        evento(new DeviceEvent(DeviceEvent.Type.PORT_ADDED, s, puerto(p)));
    }

    void quitarPuerto(String corto, long p) {
        Sw s = switches.get(dpid(corto));
        s.puertos.remove(p);
        evento(new DeviceEvent(DeviceEvent.Type.PORT_REMOVED, s, puerto(p)));
    }

    /** El switch se reinicia: pierde reglas y meters, y vuelve con el mismo datapath-id. */
    void reiniciarSwitch(String corto) {
        Sw s = switches.get(dpid(corto));
        s.disponible = false;
        evento(new DeviceEvent(DeviceEvent.Type.DEVICE_AVAILABILITY_CHANGED, s, null));
        s.instaladas.clear();
        s.metersInstalados.clear();
        onos.schedule(() -> {
            s.disponible = true;
            evento(new DeviceEvent(DeviceEvent.Type.DEVICE_AVAILABILITY_CHANGED, s, null));
        }, 300, TimeUnit.MILLISECONDS);
    }

    private void evento(DeviceEvent e) {
        onos.execute(() -> oyentesDisp.forEach(o -> o.event(e)));
    }

    private static Port puerto(long n) {
        PortNumber pn = n < 0 ? PortNumber.LOCAL : PortNumber.portNumber(n);
        return new Port() {
            public PortNumber number() { return pn; }
            public boolean isEnabled() { return true; }
        };
    }

    // ------------------------------------------------------------------ sondeos de ONOS

    private static MeterId meterDe(FlowRule r) {
        Instructions.MeterInstruction mi = r.treatment().metered();
        return mi == null ? null : mi.meterId();
    }

    private void sondeoFlujos() {
        for (Sw s : switches.values()) {
            if (!s.disponible) {
                continue;
            }
            for (DefaultFlowRule r : s.almacen.values()) {
                long c = r.id().value();
                if (s.instaladas.containsKey(c)) {
                    r.estado = FlowEntry.FlowEntryState.ADDED;
                } else {                                   // falta en el switch: ONOS la reenvía
                    r.estado = FlowEntry.FlowEntryState.PENDING_ADD;
                    MeterId m = meterDe(r);
                    if (m == null || s.metersInstalados.contains(m)) {
                        s.instaladas.put(c, r);
                        reinstaladas.incrementAndGet();
                    }
                }
            }
        }
    }

    private void sondeoMeters() {
        for (Sw s : switches.values()) {
            if (!s.disponible) {
                continue;
            }
            for (SimMeter m : s.metersAlmacen.values()) {
                if (s.metersInstalados.contains(m.id)) {
                    m.estado = MeterState.ADDED;
                } else {
                    s.metersInstalados.add(m.id);         // falta en el switch: ONOS lo reenvía
                }
            }
        }
    }

    // ------------------------------------------------------------------ FlowRuleService

    @Override
    public Iterable<FlowEntry> getFlowEntries(DeviceId d) {
        Sw s = switches.get(d);
        return s == null ? new ArrayList<>() : new ArrayList<FlowEntry>(s.almacen.values());
    }

    @Override
    public void apply(FlowRuleOperations o) {
        onos.schedule(() -> {
            boolean error = false;
            for (List<Object[]> etapa : o.etapas) {
                for (Object[] op : etapa) {
                    DefaultFlowRule r = (DefaultFlowRule) op[1];
                    Sw s = switches.get(r.deviceId());
                    long c = r.id().value();
                    if ("remove".equals(op[0])) {
                        bajas.incrementAndGet();
                        s.almacen.remove(c);
                        s.instaladas.remove(c);
                    } else {
                        altas.incrementAndGet();
                        MeterId m = meterDe(r);
                        if (m != null && !s.metersInstalados.contains(m)) {
                            rechazadas.incrementAndGet();  // OFPMMFC_UNKNOWN_METER
                            error = true;
                        } else {
                            r.estado = FlowEntry.FlowEntryState.PENDING_ADD;
                            s.almacen.put(c, r);
                            s.instaladas.put(c, r);
                        }
                    }
                }
            }
            if (o.contexto != null) {
                if (error) {
                    o.contexto.onError(o);
                } else {
                    o.contexto.onSuccess(o);
                }
            }
        }, RETARDO_OPENFLOW_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void removeFlowRulesById(ApplicationId app) {
        for (Sw s : switches.values()) {
            s.almacen.values().removeIf(r -> r.appId() == app.id());
            s.instaladas.values().removeIf(r -> r.appId() == app.id());
        }
    }

    // ------------------------------------------------------------------ MeterService

    @Override
    public Meter submit(MeterRequest r) {
        Sw s = switches.get(r.deviceId());
        SimMeter m = new SimMeter(s.id, MeterId.meterId(s.siguienteMeter.getAndIncrement()), r.appId());
        s.metersAlmacen.put(m.id, m);
        metersCreados.incrementAndGet();
        onos.schedule(() -> {
            s.metersInstalados.add(m.id);
        }, RETARDO_OPENFLOW_MS, TimeUnit.MILLISECONDS);
        return m;
    }

    @Override
    public void withdraw(MeterRequest r, MeterCellId id) {
        Sw s = switches.get(r.deviceId());
        if (s.metersAlmacen.remove((MeterId) id) != null) {
            metersRetirados.incrementAndGet();
        }
        onos.schedule(() -> {
            s.metersInstalados.remove((MeterId) id);
            s.instaladas.values().removeIf(f -> id.equals(meterDe(f)));   // el switch borra las reglas del meter
        }, RETARDO_OPENFLOW_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public Collection<Meter> getMeters(DeviceId d) {
        Sw s = switches.get(d);
        return s == null ? new ArrayList<>() : new ArrayList<Meter>(s.metersAlmacen.values());
    }

    @Override
    public Collection<Meter> getAllMeters() {
        List<Meter> l = new ArrayList<>();
        switches.values().forEach(s -> l.addAll(s.metersAlmacen.values()));
        return l;
    }

    // ------------------------------------------------------------------ dispositivos y enlaces

    @Override
    public boolean isAvailable(DeviceId d) {
        Sw s = switches.get(d);
        return s != null && s.disponible;
    }

    @Override
    public Iterable<Device> getAvailableDevices() {
        List<Device> l = new ArrayList<>();
        switches.values().stream().filter(s -> s.disponible).forEach(l::add);
        return l;
    }

    @Override
    public List<Port> getPorts(DeviceId d) {
        List<Port> l = new ArrayList<>();
        l.add(puerto(-1));                                 // el puerto LOCAL de OVS
        Sw s = switches.get(d);
        if (s != null) {
            new ArrayList<>(s.puertos).forEach(p -> l.add(puerto(p)));
        }
        return l;
    }

    @Override
    public Set<Link> getDeviceEgressLinks(DeviceId d) {
        Set<Link> r = new HashSet<>();
        enlaces.stream().filter(l -> l.a.deviceId().equals(d)).forEach(r::add);
        return r;
    }

    @Override
    public Set<Link> getDeviceLinks(DeviceId d) {
        Set<Link> r = new HashSet<>();
        enlaces.stream().filter(l -> l.a.deviceId().equals(d) || l.b.deviceId().equals(d)).forEach(r::add);
        return r;
    }

    @Override public void addListener(DeviceListener l) { oyentesDisp.add(l); }
    @Override public void removeListener(DeviceListener l) { oyentesDisp.remove(l); }
    @Override public void addListener(LinkListener l) { oyentesEnlace.add(l); }
    @Override public void removeListener(LinkListener l) { oyentesEnlace.remove(l); }

    // ------------------------------------------------------------------ el resto

    @Override
    public ApplicationId registerApplication(String nombre) {
        return apps.computeIfAbsent(nombre, n -> {
            short id = (short) (100 + apps.size());
            return new ApplicationId() {
                public short id() { return id; }
                public String name() { return n; }
                @Override public String toString() { return n; }
            };
        });
    }

    @Override public ApplicationState getState(ApplicationId app) { return estadoApp; }
    @Override public boolean isLocalMaster(DeviceId d) { return true; }
    @Override public void registerConfigFactory(ConfigFactory f) { }
    @Override public void unregisterConfigFactory(ConfigFactory f) { }
    @Override public void addListener(NetworkConfigListener l) { }
    @Override public void removeListener(NetworkConfigListener l) { }
    @Override public <S, C extends Config<S>> C getConfig(S s, Class<C> c) { return null; }
}
