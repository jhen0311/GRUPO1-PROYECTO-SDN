// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.app;

public interface ApplicationService {
    ApplicationState getState(org.onosproject.core.ApplicationId app);
}
