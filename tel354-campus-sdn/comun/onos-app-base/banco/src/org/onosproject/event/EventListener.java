// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.event;

public interface EventListener<E extends Event> {
    void event(E e);
}
