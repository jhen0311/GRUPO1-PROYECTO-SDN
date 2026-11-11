package pe.edu.pucp.tel354.api.r3;

import org.onosproject.event.ListenerService;

/** Slow-path de la regla 55 (implementa M2). Solo publica AuthorizationDenied. */
public interface AuthorizationService extends ListenerService<AuthorizationEvent, AuthorizationListener> {
}
