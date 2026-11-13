// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.slf4j;

public interface Logger {
    void info(String f, Object... a);
    void warn(String f, Object... a);
}
