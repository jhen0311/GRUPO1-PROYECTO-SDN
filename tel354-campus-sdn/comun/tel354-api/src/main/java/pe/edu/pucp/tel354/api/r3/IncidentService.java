package pe.edu.pucp.tel354.api.r3;

import org.onosproject.event.ListenerService;

import java.util.List;

/** Incidentes de R3 (implementa M5). */
public interface IncidentService extends ListenerService<IncidentEvent, IncidentListener> {

    /** Incidentes EN_INVESTIGACION del usuario. */
    List<Incidente> abiertos(int usuarioId);
}
