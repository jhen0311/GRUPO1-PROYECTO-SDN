package pe.edu.pucp.tel354.m4a;

import org.onlab.packet.Ip4Address;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.event.EventDeliveryService;
import org.onosproject.event.ListenerRegistry;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.config.basics.SubjectFactories;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.edu.pucp.tel354.api.r3.AuthorizationEvent;
import pe.edu.pucp.tel354.api.r3.AuthorizationListener;
import pe.edu.pucp.tel354.api.r3.AuthorizationService;
import pe.edu.pucp.tel354.api.r3.Destino;
import pe.edu.pucp.tel354.api.r3.Metrica;
import pe.edu.pucp.tel354.api.r3.Sospecha;
import pe.edu.pucp.tel354.api.r3.ThreatEvent;
import pe.edu.pucp.tel354.api.r3.ThreatListener;
import pe.edu.pucp.tel354.api.r3.ThreatService;
import pe.edu.pucp.tel354.api.r3.TipoAtaque;
import pe.edu.pucp.tel354.api.sesion.SesionInfo;
import pe.edu.pucp.tel354.api.sesion.SessionEvent;
import pe.edu.pucp.tel354.api.sesion.SessionListener;
import pe.edu.pucp.tel354.api.sesion.SessionService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * M4a - Ventanas deslizantes (LLSD R3, sección 5.5). Plantilla de partida funcional.
 *
 * Mantiene por sesión los destinos, los puertos por destino y los instantes de los intentos
 * denegados, y por destino las sesiones de origen (Tabla 14). Cada evento se procesa en O(1)
 * amortizado en el hilo propio de M4a. Al alcanzar un umbral publica THREAT_SUSPECTED; durante
 * la ventana siguiente, la evidencia nueva de la misma clave sale como THREAT_UPDATED (como
 * máximo una cada 5 s). BindingViolation se reenvía de inmediato como SUPLANTACION.
 */
@Component(immediate = true, service = {ThreatService.class, M4aManager.class})
public class M4aManager implements ThreatService {

    /** Resultado de procesar una alerta del IDS (lo devuelve la REST de M4a, rest/IdsRecurso). */
    public enum ResultadoIds {
        /** Atribuida a una sesión y encolada en M4a. */
        ACEPTADA,
        /** La IP de origen no tiene sesión abierta (por ejemplo, un servidor): no se atribuye. */
        SIN_SESION,
        /** El sid y la categoría no tienen un tipo de ataque asignado en la network config. */
        NO_MAPEADA,
        /** Dirección IP ilegible o campos incompletos. */
        INVALIDA
    }

    public static final String APP = "tel354.m4a";
    private static final long ACTUALIZACION_MIN_MS = 5000;

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected SessionService sessionService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected AuthorizationService authorizationService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry netcfg;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected EventDeliveryService eventDispatcher;

    private ApplicationId appId;
    private ScheduledExecutorService hilo;
    private final ListenerRegistry<ThreatEvent, ThreatListener> oyentes = new ListenerRegistry<>();

    // Tabla 14: cada elemento guarda el instante de su última aparición
    private final Map<Long, Map<String, Long>> destinos = new HashMap<>();
    private final Map<Long, Map<String, Map<String, Long>>> puertos = new HashMap<>();
    private final Map<Long, Deque<Long>> eventos = new HashMap<>();
    private final Map<String, Map<Long, Long>> origenes = new HashMap<>();
    private final Map<String, Long> alertadas = new HashMap<>();
    private final Map<String, Long> actualizadas = new HashMap<>();
    private final Map<Long, SesionInfo> sesiones = new HashMap<>();
    private final Map<Long, Long> primera = new HashMap<>();

    private final SessionListener oyenteSesiones = e -> hilo.execute(() -> alSesion(e));
    private final AuthorizationListener oyenteDenegaciones = e -> hilo.execute(() -> alDenegado(e));

    private final ConfigFactory<ApplicationId, M4aConfig> fabrica =
            new ConfigFactory<ApplicationId, M4aConfig>(SubjectFactories.APP_SUBJECT_FACTORY,
                                                        M4aConfig.class, M4aConfig.CLAVE) {
                @Override
                public M4aConfig createConfig() {
                    return new M4aConfig();
                }
            };

    @Activate
    protected void activate() {
        appId = coreService.registerApplication(APP);
        netcfg.registerConfigFactory(fabrica);
        eventDispatcher.addSink(ThreatEvent.class, oyentes);
        hilo = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "m4a-ventanas");
            t.setDaemon(true);
            return t;
        });
        hilo.scheduleWithFixedDelay(this::limpiar, 60, 60, TimeUnit.SECONDS);
        sessionService.addListener(oyenteSesiones);
        authorizationService.addListener(oyenteDenegaciones);
        log.info("M4a iniciado: W1 = {} s, W2 = {} s", w1(), w2());
    }

    @Deactivate
    protected void deactivate() {
        authorizationService.removeListener(oyenteDenegaciones);
        sessionService.removeListener(oyenteSesiones);
        hilo.shutdownNow();
        eventDispatcher.removeSink(ThreatEvent.class);
        netcfg.unregisterConfigFactory(fabrica);
        log.info("M4a detenido");
    }

    // ------------------------------------------------------------------ parámetros


    private int w1() {
        M4aConfig c = netcfg.getConfig(appId, M4aConfig.class);
        return c != null ? c.w1() : 10;
    }

    private int w2() {
        M4aConfig c = netcfg.getConfig(appId, M4aConfig.class);
        return c != null ? c.w2() : 600;
    }

    private int umbral(String n, int d) {
        M4aConfig c = netcfg.getConfig(appId, M4aConfig.class);
        return c != null ? c.umbral(n, d) : d;
    }

    // ------------------------------------------------------------------ eventos

    private void alDenegado(AuthorizationEvent e) {
        SesionInfo s = e.subject();
        long ahora = System.currentTimeMillis();
        long id = s.id();
        String d = e.ipDst().toString();
        String pp = e.proto() + "/" + e.puertoDst();
        sesiones.put(id, s);
        primera.putIfAbsent(id, ahora);
        // Topes (Tabla 14): al alcanzarlos la alarma ya se disparó; la estructura no crece más
        agregar(destinos.computeIfAbsent(id, k -> new HashMap<>()), d, ahora, umbral("D2", 24));
        agregar(puertos.computeIfAbsent(id, k -> new HashMap<>()).computeIfAbsent(d, k -> new HashMap<>()),
                pp, ahora, umbral("P2", 30));
        Deque<Long> ev = eventos.computeIfAbsent(id, k -> new ArrayDeque<>());
        ev.addLast(ahora);
        while (ev.size() > umbral("N2", 30)) {
            ev.removeFirst();
        }
        Map<Long, Long> og = origenes.computeIfAbsent(d, k -> new HashMap<>());
        if (og.containsKey(id) || og.size() < umbral("F2", 5)) {
            og.put(id, ahora);
        }
        int w1 = w1();
        int w2 = w2();
        evaluarP(s, d, w1, umbral("P1", 10), ahora);
        evaluarP(s, d, w2, umbral("P2", 30), ahora);
        evaluarD(s, w1, umbral("D1", 8), ahora);
        evaluarD(s, w2, umbral("D2", 24), ahora);
        int n = contar(ev, ahora, w2);
        if (n >= umbral("N2", 30)) {
            alarma(s, Metrica.N, n, umbral("N2", 30), w2, Collections.singletonList(destino(id, d, ahora, w2)), ahora);
        }
        int f = contar(og, ahora, w2);
        if (f >= umbral("F2", 5)) {
            for (Long origen : new ArrayList<>(og.keySet())) {
                SesionInfo so = sesiones.get(origen);
                if (so != null && ahora - og.get(origen) <= w2 * 1000L) {
                    alarma(so, Metrica.F, f, umbral("F2", 5), w2,
                           Collections.singletonList(destino(origen, d, ahora, w2)), ahora);
                }
            }
        }
    }

    private void evaluarP(SesionInfo s, String d, int w, int umbral, long ahora) {
        Map<String, Long> pd = puertos.getOrDefault(s.id(), Collections.emptyMap()).getOrDefault(d, Collections.emptyMap());
        int p = contar(pd, ahora, w);
        if (p >= umbral) {
            alarma(s, Metrica.P, p, umbral, w, Collections.singletonList(destino(s.id(), d, ahora, w)), ahora);
        }
    }

    private void evaluarD(SesionInfo s, int w, int umbral, long ahora) {
        Map<String, Long> ds = destinos.getOrDefault(s.id(), Collections.emptyMap());
        int n = contar(ds, ahora, w);
        if (n >= umbral) {
            List<Destino> l = new ArrayList<>();
            for (Map.Entry<String, Long> x : ds.entrySet()) {
                if (ahora - x.getValue() <= w * 1000L) {
                    l.add(destino(s.id(), x.getKey(), ahora, w));
                }
            }
            alarma(s, Metrica.D, n, umbral, w, l, ahora);
        }
    }

    /** Destino con el protocolo y los puertos intentados en la ventana. */
    private Destino destino(long sesion, String ip, long ahora, int w) {
        Map<String, Long> pd = puertos.getOrDefault(sesion, Collections.emptyMap())
                .getOrDefault(ip, Collections.emptyMap());
        Set<Integer> ps = new TreeSet<>();
        int proto = 0;
        for (Map.Entry<String, Long> x : pd.entrySet()) {
            if (ahora - x.getValue() > w * 1000L) {
                continue;
            }
            String[] k = x.getKey().split("/");
            proto = Integer.parseInt(k[0]);
            int puerto = Integer.parseInt(k[1]);
            if (puerto >= 0) {
                ps.add(puerto);
            }
        }
        return new Destino(Ip4Address.valueOf(ip), proto, ps);
    }

    private void alSesion(SessionEvent e) {
        SesionInfo s = e.subject();
        long ahora = System.currentTimeMillis();
        switch (e.type()) {
            case ARP_PROBE:
                sesiones.put(s.id(), s);
                primera.putIfAbsent(s.id(), ahora);
                agregar(destinos.computeIfAbsent(s.id(), k -> new HashMap<>()), e.ip().toString(), ahora,
                        umbral("D2", 24));
                evaluarD(s, w1(), umbral("D1", 8), ahora);
                evaluarD(s, w2(), umbral("D2", 24), ahora);
                break;
            case BINDING_VIOLATION:
                Sospecha sp = Sospecha.builder(s, Metrica.SUPLANTACION).valor(1).umbral(1).ventanaSeg(0)
                        .destino(Destino.soloIp(e.ip())).inequivoca(e.inequivoca())
                        .detalle("BindingViolation " + e.violacion()).tsPrimeraMs(ahora).tsUmbralMs(ahora).build();
                publicar(clave(s.id(), Metrica.SUPLANTACION, 0), sp, ahora, 1);
                break;
            case SESSION_CLOSED:
                long id = s.id();
                destinos.remove(id);
                puertos.remove(id);
                eventos.remove(id);
                primera.remove(id);
                sesiones.remove(id);
                origenes.values().forEach(m -> m.remove(id));
                alertadas.keySet().removeIf(k -> k.startsWith(id + "/"));
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ utilidades

    private static void agregar(Map<String, Long> m, String clave, long ahora, int tope) {
        if (m.containsKey(clave) || m.size() < tope) {
            m.put(clave, ahora);
        }
    }

    /** Purga perezosa: descarta lo más viejo que la ventana mayor y cuenta lo que cae en w. */
    private int contar(Map<?, Long> m, long ahora, int w) {
        long w2 = w2() * 1000L;
        m.values().removeIf(t -> ahora - t > w2);
        int n = 0;
        for (long t : m.values()) {
            if (ahora - t <= w * 1000L) {
                n++;
            }
        }
        return n;
    }

    private int contar(Deque<Long> q, long ahora, int w) {
        while (!q.isEmpty() && ahora - q.peekFirst() > w * 1000L) {
            q.removeFirst();
        }
        return q.size();
    }

    private static String clave(long sesion, Metrica m, int w) {
        return sesion + "/" + m + "/" + w;
    }

    private void alarma(SesionInfo s, Metrica m, int valor, int umbral, int w, List<Destino> ds, long ahora) {
        Sospecha sp = Sospecha.builder(s, m).valor(valor).umbral(umbral).ventanaSeg(w).destinos(ds)
                .tsPrimeraMs(primera.getOrDefault(s.id(), ahora)).tsUmbralMs(ahora).build();
        publicar(clave(s.id(), m, w), sp, ahora, w);
    }

    /** Una clave (s, métrica, ventana) no se repite durante una ventana: lo nuevo sale como actualización. */
    private void publicar(String k, Sospecha sp, long ahora, int w) {
        Long t = alertadas.get(k);
        if (t == null || ahora - t > Math.max(w, 1) * 1000L) {
            alertadas.put(k, ahora);
            actualizadas.put(k, ahora);
            log.info("M4a: ThreatSuspected {}", sp);
            eventDispatcher.post(new ThreatEvent(ThreatEvent.Type.THREAT_SUSPECTED, sp));
        } else if (ahora - actualizadas.getOrDefault(k, 0L) >= ACTUALIZACION_MIN_MS) {
            actualizadas.put(k, ahora);
            eventDispatcher.post(new ThreatEvent(ThreatEvent.Type.THREAT_UPDATED, sp));
        }
    }

    /** Cada 60 s: elimina las entradas vacías o vencidas. */
    private void limpiar() {
        long ahora = System.currentTimeMillis();
        long w2 = w2() * 1000L;
        destinos.values().forEach(m -> m.values().removeIf(t -> ahora - t > w2));
        destinos.values().removeIf(Map::isEmpty);
        puertos.values().forEach(pm -> {
            pm.values().forEach(m -> m.values().removeIf(t -> ahora - t > w2));
            pm.values().removeIf(Map::isEmpty);
        });
        puertos.values().removeIf(Map::isEmpty);
        eventos.values().forEach(q -> contar(q, ahora, (int) (w2 / 1000)));
        eventos.values().removeIf(Deque::isEmpty);
        origenes.values().forEach(m -> m.values().removeIf(t -> ahora - t > w2));
        origenes.values().removeIf(Map::isEmpty);
        alertadas.values().removeIf(t -> ahora - t > w2);
        actualizadas.values().removeIf(t -> ahora - t > w2);
    }

    // ------------------------------------------------------------------ alertas del IDS (Suricata)

    /**
     * Alerta de Suricata (llega por la REST propia de M4a, POST /onos/campus-ids/v1/alertas). La sesión
     * se resuelve en el hilo de quien llama (SessionService es seguro entre hilos) y el resto, que toca
     * los mapas, va al hilo de M4a.
     */
    public ResultadoIds alertaIds(String srcIp, String dstIp, int proto, int puerto, int sid,
                                  String categoria, String firma, int severidad, String sensor) {
        M4aConfig c = netcfg.getConfig(appId, M4aConfig.class);
        TipoAtaque tipo = c != null ? c.tipoIds(sid, categoria) : null;
        if (tipo == null) {
            log.debug("M4a: alerta del IDS sin tipo de ataque (sid {}, categoría '{}')", sid, categoria);
            return ResultadoIds.NO_MAPEADA;
        }
        final Ip4Address origen;
        final Ip4Address destino;
        try {
            origen = Ip4Address.valueOf(srcIp);
            destino = Ip4Address.valueOf(dstIp);
        } catch (IllegalArgumentException e) {
            log.warn("M4a: alerta del IDS con IP inválida (sid {}): {} -> {}", sid, srcIp, dstIp);
            return ResultadoIds.INVALIDA;
        }
        SesionInfo s = sessionService.getSessionByIp(origen);
        if (s == null) {
            log.info("M4a: alerta del IDS sin sesión para {} (sid {}), no se atribuye", origen, sid);
            return ResultadoIds.SIN_SESION;
        }
        hilo.execute(() -> alIds(s, destino, proto, puerto, tipo, sid, firma, severidad, sensor));
        return ResultadoIds.ACEPTADA;
    }

    /** Hilo de M4a: convierte la alerta en Sospecha y la publica con la misma deduplicación por ventana. */
    private void alIds(SesionInfo s, Ip4Address destino, int proto, int puerto, TipoAtaque tipo, int sid,
                       String firma, int severidad, String sensor) {
        long ahora = System.currentTimeMillis();
        sesiones.put(s.id(), s);
        primera.putIfAbsent(s.id(), ahora);
        M4aConfig c = netcfg.getConfig(appId, M4aConfig.class);
        int w = c != null ? c.idsVentana() : 60;
        // Marca para M6 (auditoría, IDS_ALERTA) y para medir: una línea por alerta aceptada
        log.info("M4a: IdsAlert sid {} sev {} tipo {} sesion {} [{} {}] -> {} proto {} puerto {} sensor {}: {}", sid,
                 severidad, tipo, s.id(), s.usuario(), s.rol(), destino, proto, puerto, sensor.isEmpty() ? "-" : sensor,
                 firma);
        Destino d = new Destino(destino, proto,
                puerto > 0 ? Collections.singleton(puerto) : Collections.<Integer>emptySet());
        Sospecha sp = Sospecha.builder(s, Metrica.IDS).tipo(tipo).valor(1).umbral(1).ventanaSeg(w)
                .destino(d).detalle("IDS sid=" + sid + " sev=" + severidad + " sensor=" + sensor + ": " + firma)
                .tsPrimeraMs(ahora).tsUmbralMs(ahora).build();
        // Una clave por (sesión, tipo): la fuerza bruta y las peticiones maliciosas no se pisan entre sí
        publicar(s.id() + "/IDS_" + tipo + "/" + w, sp, ahora, w);
    }

    // ------------------------------------------------------------------ ThreatService

    @Override
    public void addListener(ThreatListener l) {
        oyentes.addListener(l);
    }

    @Override
    public void removeListener(ThreatListener l) {
        oyentes.removeListener(l);
    }

    @Override
    public Map<String, Integer> estado(long sesionId) {
        try {
            return hilo.submit(() -> {
                long ahora = System.currentTimeMillis();
                Map<String, Integer> r = new HashMap<>();
                Map<String, Long> ds = destinos.getOrDefault(sesionId, new HashMap<>());
                r.put("D1", contar(ds, ahora, w1()));
                r.put("D2", contar(ds, ahora, w2()));
                int p1 = 0;
                int p2 = 0;
                for (Map<String, Long> pd : puertos.getOrDefault(sesionId, new HashMap<>()).values()) {
                    p1 = Math.max(p1, contar(pd, ahora, w1()));
                    p2 = Math.max(p2, contar(pd, ahora, w2()));
                }
                r.put("P1", p1);
                r.put("P2", p2);
                r.put("N2", contar(eventos.getOrDefault(sesionId, new ArrayDeque<>()), ahora, w2()));
                return r;
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
