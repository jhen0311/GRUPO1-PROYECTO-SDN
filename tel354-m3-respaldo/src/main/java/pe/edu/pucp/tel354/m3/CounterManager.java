package pe.edu.pucp.tel354.m3;

import org.onosproject.core.CoreService;
import org.onosproject.net.DeviceId;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRuleService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.edu.pucp.tel354.api.contador.CounterEvent;
import pe.edu.pucp.tel354.api.contador.CounterListener;
import pe.edu.pucp.tel354.api.contador.CounterService;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.onlab.util.Tools.groupedThreads;

/**
 * M3 · Monitoreo de contadores.
 *
 * Cada PERIODO_S segundos lee, desde el almacén de flujos de ONOS, los contadores
 * de las reglas vigiladas y publica un CounterEvent si aumentaron. No envía
 * mensajes OpenFlow propios: ONOS ya consulta las estadísticas de los switches
 * (flowPollFrequency), y M3 solo lee el resultado. No instala reglas ni usa BD.
 *
 * Cada instancia vigila lo que le pidieron los módulos de esa misma instancia,
 * porque el bus de eventos de ONOS es local: así el aviso llega al M5 que lo pidió.
 */
@Component(immediate = true, service = CounterService.class)
public class CounterManager implements CounterService {

    private static final String APP = "pe.edu.pucp.tel354.m3";
    private static final long PERIODO_S = 5;

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    /** switch -> (cookie -> {paquetes, bytes, lastSeen}); paquetes = -1 si aún no hay lectura base. */
    private final Map<DeviceId, Map<Long, long[]>> vigiladas = new ConcurrentHashMap<>();
    private final Set<CounterListener> oyentes = new CopyOnWriteArraySet<>();

    private ScheduledExecutorService sondeo;
    private ExecutorService entrega;

    /** Arranca M3 al activar la app: registra la app y programa la lectura cada 5 s. */
    @Activate
    protected void activate() {
        coreService.registerApplication(APP);
        sondeo = Executors.newSingleThreadScheduledExecutor(groupedThreads("tel354/m3", "sondeo", log));
        entrega = Executors.newSingleThreadExecutor(groupedThreads("tel354/m3", "entrega", log));
        sondeo.scheduleAtFixedRate(this::leerContadores, PERIODO_S, PERIODO_S, TimeUnit.SECONDS);

        // --- Prueba sin M5 (borrar después) ---------------------------------
        addListener(ev -> log.info("M3 prueba: {} ({} pps)", ev, String.format("%.1f", ev.pps())));
        vigilar(DeviceId.deviceId("of:00001a749039894a"), 0x200008165189dL);
        // ---------------------------------------------------------------------

        log.info("M3 iniciado (periodo {} s)", PERIODO_S);
    }

    /** Detiene M3 al desactivar la app: para los hilos y olvida lo vigilado. */
    @Deactivate
    protected void deactivate() {
        sondeo.shutdownNow();
        entrega.shutdownNow();
        vigiladas.clear();
        oyentes.clear();
        log.info("M3 detenido");
    }

    /** Agrega una regla a la lista vigilada; la primera lectura solo fija la base. */
    @Override
    public void vigilar(DeviceId dispositivo, long cookie) {
        vigiladas.computeIfAbsent(dispositivo, d -> new ConcurrentHashMap<>())
                 .putIfAbsent(cookie, new long[]{-1, -1, 0});
        log.debug("M3 vigila {} cookie=0x{}", dispositivo, Long.toHexString(cookie));
    }

    /** Quita una regla de la lista vigilada (y el switch, si ya no le queda ninguna). */
    @Override
    public void dejarDeVigilar(DeviceId dispositivo, long cookie) {
        Map<Long, long[]> reglas = vigiladas.get(dispositivo);
        if (reglas != null) {
            reglas.remove(cookie);
            if (reglas.isEmpty()) {
                vigiladas.remove(dispositivo, reglas);
            }
        }
    }

    /** Registra a un módulo (por ejemplo, M5) para que reciba los avisos. */
    @Override
    public void addListener(CounterListener oyente) {
        oyentes.add(oyente);
    }

    /** Deja de enviar avisos a ese módulo. */
    @Override
    public void removeListener(CounterListener oyente) {
        oyentes.remove(oyente);
    }

    /**
     * Se ejecuta cada 5 s: lee los contadores de las reglas vigiladas, los compara
     * con la lectura anterior y publica un aviso si aumentaron.
     */
    private void leerContadores() {
        try {
            for (Map.Entry<DeviceId, Map<Long, long[]>> porSwitch : vigiladas.entrySet()) {
                DeviceId sw = porSwitch.getKey();
                Map<Long, long[]> reglas = porSwitch.getValue();
                if (reglas.isEmpty()) {
                    continue;
                }

                // Una sola pasada por las reglas del switch; en ONOS, FlowId = cookie OpenFlow.
                Map<Long, FlowEntry> actuales = new HashMap<>();
                for (FlowEntry fe : flowRuleService.getFlowEntries(sw)) {
                    long cookie = fe.id().value();
                    if (reglas.containsKey(cookie)) {
                        actuales.put(cookie, fe);
                    }
                }

                for (Map.Entry<Long, long[]> r : reglas.entrySet()) {
                    FlowEntry fe = actuales.get(r.getKey());
                    if (fe == null) {
                        continue; // aún no instalada o ya expiró: M7 avisa con FlowExpired
                    }
                    long[] previo = r.getValue();
                    long visto = fe.lastSeen();
                    if (visto == previo[2]) {
                        continue; // ONOS todavía no trajo estadísticas nuevas de esta regla
                    }
                    long paq = fe.packets();
                    long bytes = fe.bytes();
                    if (previo[0] >= 0) {
                        // Si el contador bajó, la regla se reinstaló: se cuenta desde cero.
                        long dPaq = paq >= previo[0] ? paq - previo[0] : paq;
                        long dBytes = bytes >= previo[1] ? bytes - previo[1] : bytes;
                        if (dPaq > 0) {
                            publicar(new CounterEvent(sw, r.getKey(), dPaq, dBytes, visto - previo[2]));
                        }
                    }
                    r.setValue(new long[]{paq, bytes, visto});
                }
            }
        } catch (Exception e) {
            log.warn("M3: error al leer contadores", e);
        }
    }

    /** Entrega el aviso a todos los módulos registrados, en un hilo aparte. */
    private void publicar(CounterEvent evento) {
        for (CounterListener oyente : oyentes) {
            entrega.execute(() -> {
                try {
                    oyente.event(evento);
                } catch (Exception e) {
                    log.warn("M3: un oyente falló con {}", evento, e);
                }
            });
        }
    }
}
