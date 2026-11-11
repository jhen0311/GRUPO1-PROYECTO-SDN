package pe.edu.pucp.tel354.api.sesion;

import org.onosproject.event.EventListener;

/**
 * Oyente de eventos de sesión. Se ejecuta en el hilo de entrega de eventos de ONOS:
 * no debe bloquearse (las consultas JDBC y los Flow-Mod van al ejecutor propio de cada app).
 */
public interface SessionListener extends EventListener<SessionEvent> {
}
