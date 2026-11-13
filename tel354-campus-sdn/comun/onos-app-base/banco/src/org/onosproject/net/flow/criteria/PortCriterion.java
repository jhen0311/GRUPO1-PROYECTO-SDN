// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.flow.criteria;

import org.onosproject.net.PortNumber;

public class PortCriterion implements Criterion {
    private final PortNumber p;
    public PortCriterion(PortNumber p) { this.p = p; }
    public PortNumber port() { return p; }
}
