package pe.edu.pucp.tel354.m5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.onlab.packet.Ip4Address;
import org.onosproject.app.ApplicationService;
import org.onosproject.app.ApplicationState;
import org.onosproject.cluster.ClusterService;
import org.onosproject.cluster.NodeId;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.event.EventDeliveryService;
import org.onosproject.event.ListenerRegistry;
import org.onosproject.mastership.MastershipService;
import org.onosproject.net.DeviceId;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.config.basics.SubjectFactories;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleOperations;
import org.onosproject.net.flow.FlowRuleOperationsContext;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.IPCriterion;
import org.onosproject.net.meter.MeterId;
import org.onosproject.net.meter.MeterService;
import org.onosproject.store.cluster.messaging.ClusterCommunicationService;
import org.onosproject.store.cluster.messaging.MessageSubject;
import org.onosproject.store.serializers.KryoNamespaces;
import org.onosproject.store.service.ConsistentMap;
import org.onosproject.store.service.Serializer;
import org.onosproject.store.service.StorageService;
import org.onosproject.store.service.Versioned;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.edu.pucp.tel354.api.Cookies;
import pe.edu.pucp.tel354.api.r3.CounterEvent;
import pe.edu.pucp.tel354.api.r3.CounterListener;
import pe.edu.pucp.tel354.api.r3.CounterService;
import pe.edu.pucp.tel354.api.r3.Criticidad;
import pe.edu.pucp.tel354.api.r3.Destino;
import pe.edu.pucp.tel354.api.r3.EstadoIncidente;
import pe.edu.pucp.tel354.api.r3.Filtro;
import pe.edu.pucp.tel354.api.r3.IncidentAdminService;
import pe.edu.pucp.tel354.api.r3.IncidentEvent;
import pe.edu.pucp.tel354.api.r3.IncidentListener;
import pe.edu.pucp.tel354.api.r3.IncidentService;
import pe.edu.pucp.tel354.api.r3.Incidente;
import pe.edu.pucp.tel354.api.r3.MitigationReserveService;
import pe.edu.pucp.tel354.api.r3.Modo;
import pe.edu.pucp.tel354.api.r3.Nivel;
import pe.edu.pucp.tel354.api.r3.NivelAtaque;
import pe.edu.pucp.tel354.api.r3.Requisito;
import pe.edu.pucp.tel354.api.r3.Sospecha;
import pe.edu.pucp.tel354.api.r3.ThreatEvent;
import pe.edu.pucp.tel354.api.r3.ThreatListener;
import pe.edu.pucp.tel354.api.r3.ThreatService;
import pe.edu.pucp.tel354.api.r3.TipoAtaque;
import pe.edu.pucp.tel354.api.sesion.EstadoSesion;
import pe.edu.pucp.tel354.api.sesion.SesionInfo;
import pe.edu.pucp.tel354.api.sesion.SessionEvent;
import pe.edu.pucp.tel354.api.sesion.SessionListener;
import pe.edu.pucp.tel354.api.sesion.SessionService;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M5 - Evaluación, contención y confirmación (R3; LLSD R3, sección 5.6), para ONOS 2.7 en clúster.
 *
 * Consume ThreatSuspected (M4a), CounterDelta (M3), SessionReady y SessionClosed (M1). Evalúa el
 * nivel de ataque y la criticidad, instala las reglas 300-302 con la reserva de mitigación, y
 * resuelve los incidentes por la interfaz de TI (IncidentAdminService: consola y REST).
 *
 * Clúster: los eventos llegan a la instancia master del switch de la sesión (la que recibe sus
 * Packet-In), que es la dueña del incidente en memoria. El modo y la reserva son del clúster
 * (ConsistentMap); las operaciones de TI se aceptan en cualquier instancia: retiran las reglas por
 * cookie (el almacén de reglas es del clúster) y avisan a las demás para que olviden el incidente.
 */
@Component(immediate = true,
        service = {IncidentService.class, IncidentAdminService.class, MitigationReserveService.class, M5Pruebas.class})
public class M5Manager implements IncidentService, IncidentAdminService, MitigationReserveService, M5Pruebas {

    public static final String APP = "tel354.m5";
    private static final String HILO = "m5-eventos";
    private static final MessageSubject OLVIDAR = new MessageSubject("tel354-m5-olvidar");
    /** Identificadores temporales si PostgreSQL no responde (LLSD R3, Tabla 20). */
    private static final long ID_TEMPORAL = 0xF0000000L;

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ObjectMapper json = new ObjectMapper();

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ApplicationService applicationService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected MeterService meterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected StorageService storageService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry netcfg;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected MastershipService mastershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ClusterService clusterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ClusterCommunicationService comunicacion;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected EventDeliveryService eventDispatcher;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected SessionService sessionService;

    /** M4a y M3 son opcionales: M5 se instala y se prueba antes que ellos (tel354:r3-simular). */
    private volatile ThreatService threatService;
    private volatile CounterService counterService;

    private ApplicationId appId;
    private NodeId local;
    private volatile M5Config.Valores cfg = new M5Config.Valores();
    private volatile BaseDatosM5 bd;
    private Contencion contencion;
    private Reserva reserva;
    private ConsistentMap<String, String> estado;
    private ScheduledExecutorService hilo;
    private volatile Set<String> servicios = Collections.emptySet();
    private final AtomicLong temporales = new AtomicLong();
    /** Los incidentes vigentes ya se reconstruyeron (M1 y la BD respondieron). */
    private volatile boolean recuperado;

    /** Incidentes abiertos de los que esta instancia es dueña (o réplica), por id de cookie. */
    private final Map<Long, IncidenteVivo> abiertos = new ConcurrentHashMap<>();

    private final ListenerRegistry<IncidentEvent, IncidentListener> oyentes = new ListenerRegistry<>();
    private final SessionListener oyenteSesiones = e -> hilo.execute(() -> alSesion(e));
    private final ThreatListener oyenteAmenazas = e -> hilo.execute(() -> procesar(e.subject(),
            e.type() == ThreatEvent.Type.THREAT_UPDATED));
    private final CounterListener oyenteContadores = e -> hilo.execute(() -> alContador(e));
    private final NetworkConfigListener oyenteConfig = new OyenteConfig();

    private final ConfigFactory<ApplicationId, M5Config> fabrica =
            new ConfigFactory<ApplicationId, M5Config>(SubjectFactories.APP_SUBJECT_FACTORY,
                                                       M5Config.class, M5Config.CLAVE) {
                @Override
                public M5Config createConfig() {
                    return new M5Config();
                }
            };

    // ------------------------------------------------------------- dependencias opcionales

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC,
            unbind = "unbindThreatService")
    protected void bindThreatService(ThreatService s) {
        threatService = s;
        s.addListener(oyenteAmenazas);
        log.info("M5: suscrito a ThreatSuspected (M4a)");
    }

    protected void unbindThreatService(ThreatService s) {
        s.removeListener(oyenteAmenazas);
        if (threatService == s) {
            threatService = null;
        }
    }

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC,
            unbind = "unbindCounterService")
    protected void bindCounterService(CounterService s) {
        counterService = s;
        s.addListener(oyenteContadores);
        log.info("M5: suscrito a CounterDelta (M3)");
    }

    protected void unbindCounterService(CounterService s) {
        s.removeListener(oyenteContadores);
        if (counterService == s) {
            counterService = null;
        }
    }

    // ------------------------------------------------------------------ ciclo de vida

    @Activate
    protected void activate() {
        appId = coreService.registerApplication(APP);
        local = clusterService.getLocalNode().id();
        hilo = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, HILO);
            t.setDaemon(true);
            return t;
        });
        Serializer ser = Serializer.using(KryoNamespaces.API);
        ConsistentMap<String, String> mapaReserva = storageService.<String, String>consistentMapBuilder()
                .withName("tel354-m5-reserva").withSerializer(ser).build();
        estado = storageService.<String, String>consistentMapBuilder()
                .withName("tel354-m5-estado").withSerializer(ser).build();
        reserva = new Reserva(mapaReserva, () -> cfg.reservaPorSwitch);
        contencion = new Contencion(appId, meterService);
        eventDispatcher.addSink(IncidentEvent.class, oyentes);
        netcfg.registerConfigFactory(fabrica);
        netcfg.addListener(oyenteConfig);
        comunicacion.<String>addSubscriber(OLVIDAR, b -> new String(b, StandardCharsets.UTF_8),
                                           m -> hilo.execute(() -> olvidar(Long.parseLong(m))), hilo);
        sessionService.addListener(oyenteSesiones);
        hilo.execute(this::configurar);
        hilo.scheduleWithFixedDelay(this::vencimientos, 5, 5, TimeUnit.SECONDS);
        hilo.scheduleWithFixedDelay(this::mantenimiento, 10, 60, TimeUnit.SECONDS);
        log.info("M5 iniciado en {} (appId {})", local, appId.id());
    }

    @Deactivate
    protected void deactivate() {
        boolean appDesactivada = applicationService.getState(appId) != ApplicationState.ACTIVE;
        sessionService.removeListener(oyenteSesiones);
        comunicacion.removeSubscriber(OLVIDAR);
        netcfg.removeListener(oyenteConfig);
        hilo.shutdownNow();
        if (appDesactivada) {
            // Desactivar M5 retira todas sus contenciones y deja la reserva de R3 en cero.
            flowRuleService.removeFlowRulesById(appId);
            for (String d : reserva.switches()) {
                reserva.fijarR3(DeviceId.deviceId(d), 0);
            }
        }
        abiertos.clear();
        if (bd != null) {
            bd.cerrar();
        }
        eventDispatcher.removeSink(IncidentEvent.class);
        netcfg.unregisterConfigFactory(fabrica);
        log.info("M5 detenido en {} ({})", local, appDesactivada ? "app desactivada" : "instancia apagada");
    }

    /** Lee la network config, abre la BD y recupera los incidentes vigentes de esta instancia. */
    private void configurar() {
        M5Config c = netcfg.getConfig(appId, M5Config.class);
        cfg = c != null ? c.valores() : new M5Config.Valores();
        estado.putIfAbsent("modo", cfg.modo.name());
        BaseDatosM5 anterior = bd;
        bd = new BaseDatosM5(cfg.jdbcUrl, cfg.jdbcUsuario, cfg.jdbcClave);
        if (anterior != null) {
            anterior.cerrar();
        }
        try {
            servicios = bd.servicios();
            int n = recuperar();
            log.info("M5: modo {}, reserva {} por switch, N1 {} s, N2 {} s; {} incidentes recuperados",
                     modo(), cfg.reservaPorSwitch, cfg.n1Seg, cfg.n2Seg, n);
        } catch (Exception e) {
            log.warn("M5: PostgreSQL no disponible al arrancar ({}); se reintenta en el mantenimiento", e.toString());
        }
        conciliarReserva();
    }

    /**
     * Incidentes con contención vigente cuyo switch de origen tiene a esta instancia como master:
     * se reconstruyen desde la BD y las reglas presentes (cookie 0x06).
     */
    private int recuperar() throws Exception {
        Map<Long, List<FlowEntry>> porIncidente = new HashMap<>();
        for (FlowEntry e : flowRuleService.getFlowEntriesById(appId)) {
            long c = e.id().value();
            if (Cookies.tipo(c) == Cookies.CONTENCION) {
                porIncidente.computeIfAbsent(Cookies.id(c), k -> new ArrayList<>()).add(e);
            }
        }
        int n = 0;
        boolean faltan = false;
        for (Incidente inc : bd.vigentes(null)) {
            if (abiertos.containsKey(inc.id())) {
                continue;
            }
            SesionInfo origen = sessionService.getSession(inc.sesionId());
            if (origen == null) {
                faltan = true;                      // M1 aún no responde: se reintenta en 60 s
                continue;
            }
            if (!mastershipService.isLocalMaster(origen.dispositivo())) {
                continue;
            }
            IncidenteVivo iv = desdeBd(inc, origen);
            Map<String, Long> sesionDeIp = new HashMap<>();
            for (SesionInfo s : sessionService.getSessionsByUser(inc.usuarioId())) {
                if (s.ip() != null) {
                    sesionDeIp.put(s.ip().toString(), s.id());
                }
            }
            for (FlowEntry e : porIncidente.getOrDefault(inc.id(), Collections.<FlowEntry>emptyList())) {
                Criterion cr = e.selector().getCriterion(Criterion.Type.IPV4_SRC);
                String ip = cr instanceof IPCriterion ? ((IPCriterion) cr).ip().address().toString() : "";
                long sid = sesionDeIp.getOrDefault(ip, -1L);
                iv.reglas.computeIfAbsent(sid, k -> new ArrayList<>()).add(e);
                iv.switchDeSesion.put(sid, e.deviceId());
            }
            abiertos.put(iv.id, iv);
            n++;
        }
        recuperado = !faltan;
        return n;
    }

    private IncidenteVivo desdeBd(Incidente inc, SesionInfo origen) {
        IncidenteVivo iv = new IncidenteVivo();
        iv.id = inc.id();
        iv.usuarioId = inc.usuarioId();
        iv.usuario = inc.usuario();
        iv.origen = origen;
        iv.tipo = inc.tipo();
        iv.nivelAtaque = inc.nivelAtaque();
        iv.criticidad = inc.criticidad();
        iv.nivel = inc.nivel();
        iv.nivelCalculado = inc.nivelCalculado();
        iv.revisionPrioritaria = inc.revisionPrioritaria();
        iv.detectadoMs = inc.detectadoMs();
        iv.expiraMs = inc.expiraMs();
        try {
            iv.evidencia = (ObjectNode) json.readTree(inc.evidencia());
            for (JsonNode d : iv.evidencia.path("destinos")) {
                Set<Integer> puertos = new TreeSet<>();
                for (JsonNode p : d.path("puertos")) {
                    puertos.add(p.asInt());
                }
                iv.destinos.add(new Destino(Ip4Address.valueOf(d.path("ip").asText()), d.path("proto").asInt(0),
                                            puertos));
            }
        } catch (Exception e) {
            iv.evidencia = json.createObjectNode();
        }
        return iv;
    }

    // ---------------------------------------------------------------- evaluación

    @Override
    public void simular(Sospecha s) {
        enHilo(() -> {
            procesar(s, false);
            return null;
        }, null);
    }

    /** ThreatSuspected nuevo o actualización (LLSD R3, sección 5.6). Solo en el hilo de M5. */
    private void procesar(Sospecha s, boolean actualizacion) {
        if (s == null || s.sesion() == null || s.sesion().id() < 0) {
            return;
        }
        SesionInfo ses = sessionService.getSession(s.sesion().id());
        if (ses == null) {
            ses = s.sesion();
        }
        IncidenteVivo iv = abiertoDe(ses.id(), s.tipo());
        if (iv != null) {
            agregar(iv, s);
            return;
        }
        nuevo(ses, s, actualizacion);
    }

    private IncidenteVivo abiertoDe(long sesionId, TipoAtaque tipo) {
        for (IncidenteVivo iv : abiertos.values()) {
            if (!iv.replica && iv.tipo == tipo && iv.origen != null && iv.origen.id() == sesionId) {
                return iv;
            }
        }
        return null;
    }

    private Evaluador.Contexto contexto(IncidenteVivo iv, Sospecha s) {
        Evaluador.Contexto ctx = new Evaluador.Contexto();
        try {
            BaseDatosM5.Recuento r = bd.recuento(iv.usuarioId, iv.id);
            ctx.previos24h = r.previos;
            ctx.fuertesPrevios24h = r.fuertes;
            ctx.contencionesVigentes = r.vigentes;
        } catch (Exception e) {
            log.warn("M5: sin recuento de reincidencia ({}); se evalúa como primer incidente", e.toString());
        }
        ctx.coordinadoEnPuertoServicio = s.tipo() == TipoAtaque.ATAQUE_COORDINADO && soloPuertoServicio(iv.destinos);
        return ctx;
    }

    /** Los orígenes solo intentaron el puerto de servicio de un recurso inventariado. */
    private boolean soloPuertoServicio(List<Destino> destinos) {
        if (destinos.isEmpty()) {
            return false;
        }
        for (Destino d : destinos) {
            String proto = d.proto() == 6 ? "TCP" : d.proto() == 17 ? "UDP" : "?";
            if (d.puertos().isEmpty()) {
                return false;
            }
            for (int p : d.puertos()) {
                if (!servicios.contains(d.ip() + "/" + proto + "/" + p)) {
                    return false;
                }
            }
        }
        return true;
    }

    private List<String> ips(List<Destino> destinos) {
        List<String> l = new ArrayList<>();
        for (Destino d : destinos) {
            l.add(d.ip().toString());
        }
        return l;
    }

    private int segundos(Nivel n) {
        return n == Nivel.N2 ? cfg.n2Seg : cfg.n1Seg;
    }

    private void nuevo(SesionInfo ses, Sospecha s, boolean actualizacion) {
        IncidenteVivo iv = new IncidenteVivo();
        iv.usuarioId = ses.usuarioId();
        iv.usuario = ses.usuario();
        iv.origen = ses;
        iv.tipo = s.tipo();
        iv.destinos.addAll(s.destinos());
        Criticidad c = cfg.criticidades.maxima(ips(iv.destinos));
        Evaluador.Contexto ctx = contexto(iv, s);
        Evaluador.Resultado r = Evaluador.evaluar(iv.tipo, c, iv.destinoUnico(), s.inequivoca(), ctx);
        Modo modo = modo();
        iv.nivelAtaque = r.nivelAtaque;
        iv.criticidad = r.criticidad;
        iv.nivelCalculado = r.nivel;
        iv.revisionPrioritaria = r.revisionPrioritaria;
        iv.evidencia = evidencia(s, r, modo, actualizacion);
        Nivel objetivo = modo == Modo.ACTIVO ? r.nivel : Nivel.N0;
        iv.expiraMs = System.currentTimeMillis() + segundos(objetivo) * 1000L;
        try {
            iv.id = bd.insertar(ses.id(), iv.tipo, fuente(s), iv.nivelAtaque, objetivo, iv.evidencia.toString(),
                                iv.expiraMs);
        } catch (Exception e) {
            iv.id = ID_TEMPORAL + temporales.incrementAndGet();
            iv.evidencia.put("sin_bd", true);
            log.error("M5: no se pudo registrar el incidente ({}); se contiene con el id temporal {}", e.toString(), iv.id);
        }
        abiertos.put(iv.id, iv);
        log.info("M5: {} {} -> {} (sospecha {}; modo {})", iv, r, objetivo, s, modo);

        if (modo == Modo.ACTIVO && r.confirmarAutomatico && iv.id < ID_TEMPORAL) {
            String motivo = s.inequivoca() ? "evidencia inequívoca" : "tercer incidente Fuerte en 24 h";
            confirmarAhora(iv.id, Duration.ofHours(cfg.sancionHorasPorDefecto), "M5 (automático)", motivo, "automatico");
            return;
        }
        if (objetivo != Nivel.N0) {
            if (!aplicar(iv, objetivo)) {
                iv.evidencia.put("reserva_llena", true);
                log.warn("M5: AVISO A TI: reserva llena; el incidente {} queda en N0 (revisar con tel354:reserva)", iv.id);
                iv.nivel = Nivel.N0;
            }
        }
        guardar(iv);
        if (iv.nivel != Nivel.N0) {
            post(new IncidentEvent(IncidentEvent.Type.CONTAINMENT_APPLIED, iv.vista()));
        }
    }

    private static String fuente(Sospecha s) {
        String d = s.detalle();
        if (d != null && d.startsWith("simulado")) {
            return "SIM";
        }
        return s.tipo() == TipoAtaque.SUPLANTACION ? "M1" : "M4a";
    }

    /** Nueva evidencia de un incidente abierto: se agrega, se reevalúa y se renueva la contención. */
    private void agregar(IncidenteVivo iv, Sospecha s) {
        for (Destino d : s.destinos()) {
            boolean existe = false;
            for (int i = 0; i < iv.destinos.size(); i++) {
                Destino x = iv.destinos.get(i);
                if (x.ip().equals(d.ip())) {
                    Set<Integer> p = new TreeSet<>(x.puertos());
                    p.addAll(d.puertos());
                    iv.destinos.set(i, new Destino(x.ip(), x.proto() != 0 ? x.proto() : d.proto(), p));
                    existe = true;
                }
            }
            if (!existe) {
                iv.destinos.add(d);
            }
        }
        iv.evidencia.put("actualizaciones", iv.evidencia.path("actualizaciones").asInt(0) + 1);
        iv.evidencia.put("valor", Math.max(iv.evidencia.path("valor").asInt(0), s.valor()));
        iv.evidencia.set("destinos", destinosJson(iv.destinos));
        Criticidad c = cfg.criticidades.maxima(ips(iv.destinos));
        Evaluador.Resultado r = Evaluador.evaluar(iv.tipo, c, iv.destinoUnico(), s.inequivoca(), contexto(iv, s));
        iv.criticidad = r.criticidad;
        iv.evidencia.put("criticidad", c.name());
        iv.nivelCalculado = r.nivel.ordinal() > iv.nivelCalculado.ordinal() ? r.nivel : iv.nivelCalculado;
        iv.evidencia.put("nivel_calculado", iv.nivelCalculado.name());
        if (s.inequivoca()) {
            iv.evidencia.put("inequivoca", true);
        }
        if (modo() == Modo.ACTIVO && r.confirmarAutomatico && !iv.replica && iv.id < ID_TEMPORAL) {
            guardar(iv);
            confirmarAhora(iv.id, Duration.ofHours(cfg.sancionHorasPorDefecto), "M5 (automático)",
                           s.inequivoca() ? "evidencia inequívoca" : "tercer incidente Fuerte en 24 h", "automatico");
            return;
        }
        if (modo() == Modo.ACTIVO && r.nivel.ordinal() > iv.nivel.ordinal()) {
            Nivel anterior = iv.nivel;
            if (aplicar(iv, r.nivel)) {
                log.info("M5: {} sube de {} a {} con la nueva evidencia", iv, anterior, r.nivel);
                post(new IncidentEvent(IncidentEvent.Type.CONTAINMENT_APPLIED, iv.vista()));
            } else {
                iv.evidencia.put("reserva_llena", true);
            }
        } else if (iv.nivel == Nivel.N1 || iv.nivel == Nivel.N2) {
            renovar(iv, "nueva evidencia");
        } else {
            iv.expiraMs = System.currentTimeMillis() + cfg.n1Seg * 1000L;     // ventana de agregación de N0
        }
        guardar(iv);
    }

    private ArrayNode destinosJson(List<Destino> destinos) {
        ArrayNode a = json.createArrayNode();
        for (Destino d : destinos) {
            ObjectNode o = a.addObject();
            o.put("ip", d.ip().toString());
            if (d.proto() != 0) {
                o.put("proto", d.proto());
            }
            ArrayNode p = o.putArray("puertos");
            for (int x : d.puertos()) {
                p.add(x);
            }
        }
        return a;
    }

    private ObjectNode evidencia(Sospecha s, Evaluador.Resultado r, Modo modo, boolean actualizacion) {
        ObjectNode e = json.createObjectNode();
        e.put("metrica", s.metrica().name());
        e.put("ventana", s.ventanaSeg());
        e.put("valor", s.valor());
        e.put("umbral", s.umbral());
        e.set("destinos", destinosJson(s.destinos()));
        e.put("criticidad", r.criticidad.name());
        e.put("nivel_calculado", r.nivel.name());
        e.put("reincidencia", r.reincidencia);
        e.put("revision_prioritaria", r.revisionPrioritaria);
        e.put("inequivoca", s.inequivoca());
        e.put("modo", modo.name());
        e.put("nodo", local.id());
        ArrayNode aj = e.putArray("ajustes");
        for (String a : r.ajustes) {
            aj.add(a);
        }
        if (s.tsPrimeraMs() > 0) {
            e.put("ts_primera", Instant.ofEpochMilli(s.tsPrimeraMs()).toString());
        }
        e.put("ts_umbral", Instant.ofEpochMilli(s.tsUmbralMs()).toString());
        if (s.detalle() != null) {
            e.put("detalle", s.detalle());
        }
        if (actualizacion) {
            e.put("desde_actualizacion", true);
        }
        return e;
    }

    private void guardar(IncidenteVivo iv) {
        if (iv.id >= ID_TEMPORAL || iv.replica) {
            return;
        }
        try {
            bd.actualizar(iv.id, iv.nivel, iv.evidencia.toString(), iv.expiraMs);
        } catch (Exception e) {
            log.warn("M5: no se pudo actualizar el incidente {}: {}", iv.id, e.toString());
        }
    }

    // ---------------------------------------------------------------- contención

    /**
     * Aplica N1 (sesión de origen) o N2 (todas las sesiones activas del usuario) con la reserva.
     * Al pasar a N2 se retiran en la misma operación las N1 de los demás incidentes del usuario.
     *
     * @return false si la reserva de algún switch no alcanza (el nivel no cambia)
     */
    private boolean aplicar(IncidenteVivo iv, Nivel nivel) {
        List<SesionInfo> objetivo = new ArrayList<>();
        if (nivel == Nivel.N1) {
            SesionInfo o = sessionService.getSession(iv.origen.id());
            if (o != null && o.estado() == EstadoSesion.ACTIVA && o.ip() != null) {
                objetivo.add(o);
            }
        } else {
            for (SesionInfo s : sessionService.getSessionsByUser(iv.usuarioId)) {
                if (s.estado() == EstadoSesion.ACTIVA && s.ip() != null) {
                    objetivo.add(s);
                }
            }
        }
        int seg = segundos(nivel);
        // Reglas que salen: las de este incidente y, en N2, las N1 de otros incidentes del usuario
        List<IncidenteVivo> absorbidos = new ArrayList<>();
        Map<DeviceId, Integer> menos = new HashMap<>();
        List<FlowRule> salen = new ArrayList<>();
        for (IncidenteVivo x : abiertos.values()) {
            boolean mismo = x == iv;
            boolean absorbe = nivel == Nivel.N2 && x.usuarioId == iv.usuarioId && x.nivel == Nivel.N1 && !x.replica;
            if (!mismo && !absorbe) {
                continue;
            }
            for (Map.Entry<Long, List<FlowRule>> e : x.reglas.entrySet()) {
                DeviceId d = x.switchDeSesion.get(e.getKey());
                menos.merge(d, e.getValue().size(), Integer::sum);
                salen.addAll(e.getValue());
            }
            if (!mismo) {
                absorbidos.add(x);
            }
        }
        // Reglas que entran
        Map<Long, List<FlowRule>> nuevas = new HashMap<>();
        Map<Long, DeviceId> switches = new HashMap<>();
        Map<DeviceId, Integer> mas = new HashMap<>();
        int k = 0;
        for (SesionInfo s : objetivo) {
            List<FlowRule> r;
            if (nivel == Nivel.N1) {
                if (iv.destinos.isEmpty()) {
                    return false;
                }
                r = contencion.n1(iv.id, k, s, iv.destinos.get(0), iv.tipo, seg);
            } else {
                MeterId m = contencion.meterContencion(s.dispositivo(), cfg.contencionKbps);
                r = contencion.n2(iv.id, k, s, m, seg);
            }
            k = Math.min(k + 1, 63);
            nuevas.put(s.id(), r);
            switches.put(s.id(), s.dispositivo());
            mas.merge(s.dispositivo(), r.size(), Integer::sum);
        }
        // Reserva por switch con las entradas netas (paso 1 del algoritmo de la sección 4.4)
        Map<DeviceId, Integer> reservadas = new HashMap<>();
        Set<DeviceId> todos = new HashSet<>(mas.keySet());
        todos.addAll(menos.keySet());
        for (DeviceId d : todos) {
            int neto = mas.getOrDefault(d, 0) - menos.getOrDefault(d, 0);
            if (neto > 0) {
                if (!reserva.reservar(d, neto, Requisito.R3)) {
                    reservadas.forEach((dd, n) -> reserva.liberar(dd, n, Requisito.R3));
                    log.warn("M5: reserva insuficiente en {} ({} libres, {} necesarias)", d, reserva.libres(d), neto);
                    return false;
                }
                reservadas.put(d, neto);
            }
        }
        FlowRuleOperations.Builder ops = FlowRuleOperations.builder();
        for (FlowRule r : salen) {
            ops.remove(r);
        }
        if (!salen.isEmpty()) {
            ops.newStage();
        }
        nuevas.values().forEach(l -> l.forEach(ops::add));
        flowRuleService.apply(ops.build(contexto("aplicar " + nivel + " del incidente " + iv.id)));
        for (DeviceId d : todos) {
            int neto = mas.getOrDefault(d, 0) - menos.getOrDefault(d, 0);
            if (neto < 0) {
                reserva.liberar(d, -neto, Requisito.R3);
            }
        }
        dejarDeVigilar(iv);
        iv.reglas.clear();
        iv.switchDeSesion.clear();
        iv.reglas.putAll(nuevas);
        iv.switchDeSesion.putAll(switches);
        for (IncidenteVivo x : absorbidos) {
            dejarDeVigilar(x);
            x.reglas.clear();
            x.switchDeSesion.clear();
            x.evidencia.put("absorbido_por", iv.id);
            guardar(x);
        }
        iv.nivel = nivel;
        iv.expiraMs = System.currentTimeMillis() + seg * 1000L;
        vigilar(iv);
        log.info("M5: {} aplicado a {} sesiones ({} reglas)", nivel, objetivo.size(),
                 nuevas.values().stream().mapToInt(List::size).sum());
        return true;
    }

    /** La regla 301 hacia 10.0.0.0/8 de cada sesión en N2 la vigila M3 (renovación por CounterDelta). */
    private void vigilar(IncidenteVivo iv) {
        CounterService m3 = counterService;
        if (m3 == null || iv.nivel != Nivel.N2) {
            return;
        }
        for (List<FlowRule> l : iv.reglas.values()) {
            for (FlowRule r : l) {
                if (Cookies.indice(r.id().value()) % 4 == Cookies.IDX_301_USUARIOS) {
                    m3.vigilar(r.id(), r.deviceId());
                }
            }
        }
    }

    private void dejarDeVigilar(IncidenteVivo iv) {
        CounterService m3 = counterService;
        if (m3 == null) {
            return;
        }
        for (List<FlowRule> l : iv.reglas.values()) {
            for (FlowRule r : l) {
                if (Cookies.indice(r.id().value()) % 4 == Cookies.IDX_301_USUARIOS) {
                    m3.dejarDeVigilar(r.id());
                }
            }
        }
    }

    /** Renovación en dos etapas (retirar e instalar con un nuevo hard_timeout; LLSD R3, sección 4.5). */
    private void renovar(IncidenteVivo iv, String causa) {
        int seg = segundos(iv.nivel);
        FlowRuleOperations.Builder ops = FlowRuleOperations.builder();
        Map<Long, List<FlowRule>> nuevas = new HashMap<>();
        for (Map.Entry<Long, List<FlowRule>> e : iv.reglas.entrySet()) {
            List<FlowRule> l = new ArrayList<>();
            for (FlowRule r : e.getValue()) {
                ops.remove(r);
                l.add(Contencion.conTimeout(r, seg));
            }
            nuevas.put(e.getKey(), l);
        }
        if (nuevas.isEmpty()) {
            iv.expiraMs = System.currentTimeMillis() + seg * 1000L;
            return;
        }
        ops.newStage();
        nuevas.values().forEach(l -> l.forEach(ops::add));
        flowRuleService.apply(ops.build(contexto("renovar el incidente " + iv.id)));
        iv.reglas.putAll(nuevas);
        iv.expiraMs = System.currentTimeMillis() + seg * 1000L;
        iv.renovaciones++;
        iv.evidencia.put("renovaciones", iv.renovaciones);
        log.info("M5: {} renovado por {} s ({})", iv, seg, causa);
    }

    private FlowRuleOperationsContext contexto(String que) {
        return new FlowRuleOperationsContext() {
            @Override
            public void onError(FlowRuleOperations ops) {
                log.warn("M5: el switch rechazó reglas al {}: {}", que, ops);
            }
        };
    }

    /** Retira las reglas de una sesión del incidente y libera su reserva. */
    private void retirarSesion(IncidenteVivo iv, long sesionId) {
        List<FlowRule> l = iv.reglas.remove(sesionId);
        DeviceId d = iv.switchDeSesion.remove(sesionId);
        if (l == null || l.isEmpty()) {
            return;
        }
        CounterService m3 = counterService;
        if (m3 != null) {
            for (FlowRule r : l) {
                m3.dejarDeVigilar(r.id());
            }
        }
        flowRuleService.removeFlowRules(l.toArray(new FlowRule[0]));
        reserva.liberar(d, l.size(), Requisito.R3);
    }

    // ---------------------------------------------------------------- eventos de M1 y M3

    private void alSesion(SessionEvent e) {
        SesionInfo s = e.subject();
        if (e.type() == SessionEvent.Type.SESSION_CLOSED) {
            // Se retiran sus reglas 0x06; el incidente sigue abierto (LLSD R3, sección 5.6)
            for (IncidenteVivo iv : abiertos.values()) {
                if (iv.reglas.containsKey(s.id())) {
                    retirarSesion(iv, s.id());
                    log.info("M5: sesión {} cerrada: reglas de {} retiradas", s.id(), iv);
                }
            }
        } else if (e.type() == SessionEvent.Type.SESSION_READY) {
            reconexion(s);
        }
    }

    /** Reconectarse no sirve para eludir la contención: se aplica la vigente a la sesión nueva. */
    private void reconexion(SesionInfo s) {
        long ahora = System.currentTimeMillis();
        Set<Long> atendidos = new HashSet<>();
        for (IncidenteVivo iv : abiertos.values()) {
            if (iv.usuarioId == s.usuarioId() && iv.expiraMs > ahora
                    && (iv.nivel == Nivel.N2 || iv.nivel == Nivel.N1)) {
                aplicarASesion(iv, s);
                atendidos.add(iv.id);
            }
        }
        try {
            for (Incidente inc : bd.vigentes(s.usuarioId())) {
                if (atendidos.contains(inc.id()) || abiertos.containsKey(inc.id())) {
                    continue;
                }
                IncidenteVivo r = desdeBd(inc, sessionService.getSession(inc.sesionId()));
                r.replica = true;           // el dueño está en otra instancia
                abiertos.put(r.id, r);
                aplicarASesion(r, s);
            }
        } catch (Exception e) {
            log.warn("M5: no se pudo consultar la contención vigente de {}: {}", s.usuario(), e.toString());
        }
    }

    private void aplicarASesion(IncidenteVivo iv, SesionInfo s) {
        if (iv.reglas.containsKey(s.id()) || s.ip() == null) {
            return;
        }
        int seg = (int) Math.max(1, (iv.expiraMs - System.currentTimeMillis()) / 1000);
        int k = Math.min(iv.reglas.size(), 63);
        List<FlowRule> r;
        if (iv.nivel == Nivel.N2) {
            r = contencion.n2(iv.id, k, s, contencion.meterContencion(s.dispositivo(), cfg.contencionKbps), seg);
        } else if (!iv.destinos.isEmpty()) {
            r = contencion.n1(iv.id, k, s, iv.destinos.get(0), iv.tipo, seg);
        } else {
            return;
        }
        if (!reserva.reservar(s.dispositivo(), r.size(), Requisito.R3)) {
            log.warn("M5: AVISO A TI: reserva llena en {}; la sesión {} de {} no recibe la contención de {}",
                     s.dispositivo(), s.id(), s.usuario(), iv);
            return;
        }
        FlowRuleOperations.Builder ops = FlowRuleOperations.builder();
        r.forEach(ops::add);
        flowRuleService.apply(ops.build(contexto("aplicar a la sesión " + s.id())));
        iv.reglas.put(s.id(), r);
        iv.switchDeSesion.put(s.id(), s.dispositivo());
        vigilar(iv);
        log.info("M5: contención {} de {} aplicada a la sesión nueva {} ({} s restantes)", iv.nivel, iv, s.id(), seg);
    }

    private void alContador(CounterEvent e) {
        long c = e.subject().value();
        if (!Cookies.es(c, appId, Cookies.CONTENCION) || Cookies.indice(c) % 4 != Cookies.IDX_301_USUARIOS) {
            return;
        }
        IncidenteVivo iv = abiertos.get(Cookies.id(c));
        if (iv != null && iv.nivel == Nivel.N2 && !iv.replica) {
            renovar(iv, "CounterDelta +" + e.deltaPaquetes());
            guardar(iv);
        }
    }

    @Override
    public boolean simularContador(long incidenteId) {
        return Boolean.TRUE.equals(enHilo(() -> {
            IncidenteVivo iv = abiertos.get(incidenteId);
            if (iv == null || iv.nivel != Nivel.N2) {
                return false;
            }
            renovar(iv, "CounterDelta simulado");
            guardar(iv);
            return true;
        }, false));
    }

    // ---------------------------------------------------------------- vencimiento

    /** Cada 5 s: contenciones que vencieron sin renovación -> ContainmentLifted. */
    private void vencimientos() {
        long ahora = System.currentTimeMillis();
        for (IncidenteVivo iv : new ArrayList<>(abiertos.values())) {
            if (iv.expiraMs > ahora) {
                continue;
            }
            for (Long sid : new ArrayList<>(iv.reglas.keySet())) {
                retirarSesion(iv, sid);          // ya vencieron en el switch; se libera la reserva
            }
            abiertos.remove(iv.id);
            if (iv.replica || iv.id >= ID_TEMPORAL) {
                continue;
            }
            try {
                bd.mitigar(iv.id, "VENCIMIENTO", "M5", null);
            } catch (Exception e) {
                log.warn("M5: no se pudo cerrar el incidente vencido {}: {}", iv.id, e.toString());
            }
            log.info("M5: {} venció sin renovación (ContainmentLifted)", iv);
            if (iv.nivel != Nivel.N0) {
                post(new IncidentEvent(IncidentEvent.Type.CONTAINMENT_LIFTED, iv.vista()));
            }
        }
    }

    /** Cada 60 s: cierre de vencidos en la BD, inventario de servicios y conciliación de la reserva. */
    private void mantenimiento() {
        try {
            int n = bd.cerrarVencidos();
            if (n > 0) {
                log.info("M5: {} incidentes vencidos cerrados en la BD", n);
            }
            servicios = bd.servicios();
            if (!recuperado) {
                int k = recuperar();
                if (k > 0) {
                    log.info("M5: {} incidentes vigentes recuperados", k);
                }
            }
        } catch (Exception e) {
            log.warn("M5: mantenimiento sin BD: {}", e.toString());
        }
        conciliarReserva();
    }

    /**
     * La verdad de la reserva de R3 son las reglas 0x06 presentes: cada instancia corrige el
     * contador de los switches de los que es master (por ejemplo, tras apagarse otra instancia).
     */
    private void conciliarReserva() {
        Map<DeviceId, Integer> cuenta = new HashMap<>();
        for (FlowEntry e : flowRuleService.getFlowEntriesById(appId)) {
            if (Cookies.tipo(e.id().value()) == Cookies.CONTENCION) {
                cuenta.merge(e.deviceId(), 1, Integer::sum);
            }
        }
        Set<DeviceId> ds = new HashSet<>(cuenta.keySet());
        for (String d : reserva.switches()) {
            ds.add(DeviceId.deviceId(d));
        }
        for (DeviceId d : ds) {
            if (!mastershipService.isLocalMaster(d)) {
                continue;
            }
            int real = cuenta.getOrDefault(d, 0);
            int usadas = reserva.usadas(d).get(Requisito.R3);
            if (real != usadas) {
                reserva.fijarR3(d, real);
                log.info("M5: reserva de R3 en {} conciliada: {} -> {}", d, usadas, real);
            }
        }
    }

    // ------------------------------------------------------- IncidentAdminService (TI)

    @Override
    public List<Incidente> listar(Filtro filtro) {
        try {
            return bd.listar(filtro);
        } catch (Exception e) {
            log.warn("M5: listar: {}", e.toString());
            List<Incidente> l = new ArrayList<>();
            abiertos.values().forEach(iv -> l.add(iv.vista()));
            return l;
        }
    }

    @Override
    public Incidente detalle(long incidenteId) {
        try {
            Incidente i = bd.detalle(incidenteId);
            IncidenteVivo iv = abiertos.get(incidenteId);
            if (i != null && iv != null) {
                return Incidente.builder().id(i.id()).sesionId(i.sesionId()).usuarioId(i.usuarioId())
                        .usuario(i.usuario()).tipo(i.tipo()).nivelAtaque(i.nivelAtaque()).criticidad(i.criticidad())
                        .nivel(i.nivel()).nivelCalculado(i.nivelCalculado()).estado(i.estado())
                        .revisionPrioritaria(i.revisionPrioritaria()).detectadoMs(i.detectadoMs())
                        .expiraMs(i.expiraMs()).mitigadoMs(i.mitigadoMs()).evidencia(i.evidencia())
                        .sesiones(new ArrayList<>(iv.reglas.keySet())).build();
            }
            return i;
        } catch (Exception e) {
            IncidenteVivo iv = abiertos.get(incidenteId);
            return iv != null ? iv.vista() : null;
        }
    }

    @Override
    public Incidente confirmar(long incidenteId, Duration sancion, String solicitante, String motivo, String via) {
        return enHilo(() -> confirmarAhora(incidenteId, sancion, solicitante, motivo, via), null);
    }

    /**
     * Confirmación (LLSD R3, sección 5.6), en este orden: 1) retirar las reglas 0x06 del incidente;
     * 2) fila de cuarentena; 3) accion = N3 y QuarantineConfirmed; 4) paso forzado de M1.
     */
    private Incidente confirmarAhora(long id, Duration sancion, String solicitante, String motivo, String via) {
        Incidente inc;
        try {
            inc = bd.detalle(id);
        } catch (Exception e) {
            throw new IllegalStateException("PostgreSQL no disponible: " + e.getMessage(), e);
        }
        if (inc == null) {
            throw new IllegalArgumentException("No existe el incidente " + id);
        }
        if (inc.estado() == EstadoIncidente.CONFIRMADO) {
            return inc;
        }
        retirarPorCookie(id);
        long fin;
        try {
            fin = bd.confirmar(id, inc.usuarioId(), sancion.getSeconds(), solicitante, motivo);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo registrar la cuarentena: " + e.getMessage(), e);
        }
        Incidente conf = detalle(id);
        log.warn("M5: incidente {} CONFIRMADO por {} vía {} ({}): cuarentena de {} hasta {}", id, solicitante, via,
                 motivo, inc.usuario(), Instant.ofEpochMilli(fin));
        post(new IncidentEvent(IncidentEvent.Type.QUARANTINE_CONFIRMED, conf, fin, solicitante, motivo, "confirmar"));
        post(new IncidentEvent(IncidentEvent.Type.ADMIN_OPERATION, conf, fin, solicitante, motivo, "confirmar (" + via + ")"));
        int n = sessionService.aplicarCuarentena(inc.usuarioId(), id);
        log.info("M5: paso forzado a cuarentena pedido a M1: {} sesiones de {}", n, inc.usuario());
        return conf;
    }

    @Override
    public Incidente descartar(long incidenteId, String solicitante, String motivo, String via) {
        return enHilo(() -> {
            Incidente inc = bd.detalle(incidenteId);
            if (inc == null) {
                throw new IllegalArgumentException("No existe el incidente " + incidenteId);
            }
            if (inc.estado() != EstadoIncidente.EN_INVESTIGACION) {
                return inc;
            }
            retirarPorCookie(incidenteId);
            bd.mitigar(incidenteId, "DESCARTE", solicitante, motivo);
            Incidente d = bd.detalle(incidenteId);
            log.info("M5: incidente {} DESCARTADO por {} vía {} ({})", incidenteId, solicitante, via, motivo);
            post(new IncidentEvent(IncidentEvent.Type.CONTAINMENT_LIFTED, d, 0, solicitante, motivo, "descartar"));
            post(new IncidentEvent(IncidentEvent.Type.ADMIN_OPERATION, d, 0, solicitante, motivo, "descartar (" + via + ")"));
            return d;
        }, null);
    }

    /**
     * Retira las reglas del incidente en todo el clúster por su cookie, libera su reserva y pide a
     * todas las instancias que lo olviden (sin volver a liberar).
     */
    private void retirarPorCookie(long id) {
        Map<DeviceId, List<FlowRule>> porSwitch = new HashMap<>();
        for (FlowEntry e : flowRuleService.getFlowEntriesById(appId)) {
            long c = e.id().value();
            if (Cookies.tipo(c) == Cookies.CONTENCION && Cookies.id(c) == id) {
                porSwitch.computeIfAbsent(e.deviceId(), k -> new ArrayList<>()).add(e);
            }
        }
        porSwitch.forEach((d, l) -> {
            flowRuleService.removeFlowRules(l.toArray(new FlowRule[0]));
            reserva.liberar(d, l.size(), Requisito.R3);
        });
        olvidar(id);
        comunicacion.broadcast(Long.toString(id), OLVIDAR, s -> s.getBytes(StandardCharsets.UTF_8));
    }

    private void olvidar(long id) {
        IncidenteVivo iv = abiertos.remove(id);
        if (iv != null) {
            dejarDeVigilar(iv);
            log.info("M5: {} resuelto por TI; se olvida en {}", iv, local);
        }
    }

    @Override
    public void modo(Modo modo, String solicitante, String via) {
        estado.put("modo", modo.name());
        log.info("M5: modo {} fijado por {} vía {}", modo, solicitante, via);
        post(new IncidentEvent(IncidentEvent.Type.ADMIN_OPERATION, Incidente.builder().build(), 0, solicitante,
                               "modo " + modo, "modo (" + via + ")"));
    }

    @Override
    public Modo modo() {
        String m = Versioned.valueOrNull(estado.get("modo"));
        return m != null ? Modo.valueOf(m) : cfg.modo;
    }

    // ------------------------------------------------------------- IncidentService

    @Override
    public void addListener(IncidentListener l) {
        oyentes.addListener(l);
    }

    @Override
    public void removeListener(IncidentListener l) {
        oyentes.removeListener(l);
    }

    @Override
    public List<Incidente> abiertos(int usuarioId) {
        return listar(new Filtro(true, usuarioId, null, 100));
    }

    private void post(IncidentEvent e) {
        eventDispatcher.post(e);
    }

    // ---------------------------------------------------- MitigationReserveService (R4)

    @Override
    public boolean reservar(DeviceId d, int entradas, Requisito r) {
        return reserva.reservar(d, entradas, r);
    }

    @Override
    public void liberar(DeviceId d, int entradas, Requisito r) {
        reserva.liberar(d, entradas, r);
    }

    @Override
    public int libres(DeviceId d) {
        return reserva.libres(d);
    }

    @Override
    public int capacidad(DeviceId d) {
        return reserva.capacidad(d);
    }

    @Override
    public Map<Requisito, Integer> usadas(DeviceId d) {
        return reserva.usadas(d);
    }

    /** Switches con reserva registrada (comando tel354:reserva). */
    public List<DeviceId> switchesConReserva() {
        List<DeviceId> l = new ArrayList<>();
        for (String s : reserva.switches()) {
            l.add(DeviceId.deviceId(s));
        }
        return l;
    }

    // ------------------------------------------------------------------ utilidades

    /** Ejecuta en el hilo de M5 (o directamente si ya se está en él) y espera el resultado. */
    private <T> T enHilo(Callable<T> c, T siFalla) {
        try {
            if (HILO.equals(Thread.currentThread().getName())) {
                return c.call();
            }
            return hilo.submit(c).get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable t = e.getCause();
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
            throw new IllegalStateException(t);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.warn("M5: operación fallida: {}", e.toString());
            return siFalla;
        }
    }

    private class OyenteConfig implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            if (event.configClass() == M5Config.class && (event.type() == NetworkConfigEvent.Type.CONFIG_ADDED
                    || event.type() == NetworkConfigEvent.Type.CONFIG_UPDATED)) {
                hilo.execute(() -> {
                    configurar();
                    // el modo de la network config prevalece al cargarla
                    estado.put("modo", cfg.modo.name());
                });
            }
        }
    }
}
