package pe.edu.pucp.tel354.m3;

import org.onosproject.core.CoreService;
import org.onosproject.event.EventDeliveryService;
import org.onosproject.event.ListenerRegistry;
import org.onosproject.net.DeviceId;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowId;
import org.onosproject.net.flow.FlowRuleService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.edu.pucp.tel354.api.r3.CounterEvent;
import pe.edu.pucp.tel354.api.r3.CounterListener;
import pe.edu.pucp.tel354.api.r3.CounterService;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * M3 - Lectura de contadores (LLSD R3, sección 5.4). Plantilla de partida funcional.
 *
 * Cada P = 5 s compara packets() de cada regla vigilada con la lectura anterior y publica
 * CounterDelta si aumentó. Lee del almacén de ONOS (el proveedor OpenFlow ya sondea las
 * estadísticas), sin mensajes extra a los switches. Si el contador baja, la regla se reinstaló
 * (renovación de M5 en dos etapas, misma cookie): se toma la nueva lectura como base.
 */
@Component(immediate = true, service = CounterService.class)
public class M3Manager implements CounterService {

    public static final String APP = "tel354.m3";
    private static final int PERIODO_SEG = 5;

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected EventDeliveryService eventDispatcher;

    private static final class Vigilada {
        final DeviceId dispositivo;
        long paquetes = -1;
        long ts;

        Vigilada(DeviceId d) {
            dispositivo = d;
        }
    }

    private final Map<FlowId, Vigilada> vigiladas = new ConcurrentHashMap<>();
    private final ListenerRegistry<CounterEvent, CounterListener> oyentes = new ListenerRegistry<>();
    private ScheduledExecutorService hilo;

    @Activate
    protected void activate() {
        coreService.registerApplication(APP);
        eventDispatcher.addSink(CounterEvent.class, oyentes);
        hilo = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "m3-contadores");
            t.setDaemon(true);
            return t;
        });
        hilo.scheduleWithFixedDelay(this::leer, PERIODO_SEG, PERIODO_SEG, TimeUnit.SECONDS);
        log.info("M3 iniciado (periodo {} s)", PERIODO_SEG);
    }

    @Deactivate
    protected void deactivate() {
        hilo.shutdownNow();
        eventDispatcher.removeSink(CounterEvent.class);
        vigiladas.clear();
        log.info("M3 detenido");
    }

    @Override
    public void vigilar(FlowId regla, DeviceId dispositivo) {
        vigiladas.putIfAbsent(regla, new Vigilada(dispositivo));
    }

    @Override
    public void dejarDeVigilar(FlowId regla) {
        vigiladas.remove(regla);
    }

    @Override
    public void addListener(CounterListener l) {
        oyentes.addListener(l);
    }

    @Override
    public void removeListener(CounterListener l) {
        oyentes.removeListener(l);
    }

    private void leer() {
        try {
            Set<DeviceId> ds = new HashSet<>();
            vigiladas.values().forEach(v -> ds.add(v.dispositivo));
            long ahora = System.currentTimeMillis();
            for (DeviceId d : ds) {
                Map<FlowId, Long> lectura = new HashMap<>();
                for (FlowEntry e : flowRuleService.getFlowEntries(d)) {
                    if (vigiladas.containsKey(e.id())) {
                        lectura.merge(e.id(), e.packets(), Long::sum);
                    }
                }
                for (Map.Entry<FlowId, Long> x : lectura.entrySet()) {
                    Vigilada v = vigiladas.get(x.getKey());
                    if (v == null) {
                        continue;
                    }
                    long p = x.getValue();
                    if (v.paquetes >= 0 && p > v.paquetes) {
                        eventDispatcher.post(new CounterEvent(x.getKey(), d, p - v.paquetes, ahora - v.ts));
                    }
                    v.paquetes = p;           // también cuando baja: regla reinstalada
                    v.ts = ahora;
                }
            }
        } catch (Exception e) {
            log.warn("M3: lectura fallida: {}", e.toString());
        }
    }
}
