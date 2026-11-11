package pe.edu.pucp.tel354.api.r3;

import org.onosproject.event.ListenerService;

import java.util.Map;

/** Ventanas deslizantes (implementa M4a). */
public interface ThreatService extends ListenerService<ThreatEvent, ThreatListener> {

    /** Valor actual de cada métrica de la sesión en W1 y W2 (claves "P1", "D1", "P2", "D2", "N2"). */
    Map<String, Integer> estado(long sesionId);
}
