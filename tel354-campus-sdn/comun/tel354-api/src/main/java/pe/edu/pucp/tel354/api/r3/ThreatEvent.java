package pe.edu.pucp.tel354.api.r3;

import org.onosproject.event.AbstractEvent;

/**
 * THREAT_SUSPECTED: una métrica alcanzó su umbral (o llegó una suplantación).
 * THREAT_UPDATED: evidencia nueva de una alarma ya publicada en la misma ventana; M5 la agrega
 * al incidente abierto.
 */
public class ThreatEvent extends AbstractEvent<ThreatEvent.Type, Sospecha> {

    public enum Type { THREAT_SUSPECTED, THREAT_UPDATED }

    public ThreatEvent(Type tipo, Sospecha sospecha) {
        super(tipo, sospecha);
    }

    @Override
    public String toString() {
        return type() + " " + subject();
    }
}
