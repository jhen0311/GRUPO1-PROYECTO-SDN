// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.flow;

public interface FlowEntry extends FlowRule {
    enum FlowEntryState { PENDING_ADD, ADDED, PENDING_REMOVE, REMOVED, FAILED }
    FlowEntryState state();
}
