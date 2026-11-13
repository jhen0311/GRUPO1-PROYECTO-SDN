// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.event;

public interface Event<T extends Enum<T>, S> {
    T type();
    S subject();
    long time();
}
