// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.apache.karaf.shell.api.action;

public @interface Command {
    String scope();
    String name();
    String description();
}
