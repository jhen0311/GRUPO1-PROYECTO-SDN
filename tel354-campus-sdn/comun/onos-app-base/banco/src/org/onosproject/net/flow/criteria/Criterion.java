// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.flow.criteria;

public interface Criterion {
    enum Type { IN_PORT, VLAN_VID, ETH_TYPE, IPV4_DST }
}
