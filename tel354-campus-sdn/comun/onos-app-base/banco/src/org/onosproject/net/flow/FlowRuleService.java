// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.flow;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.DeviceId;

public interface FlowRuleService {
    Iterable<FlowEntry> getFlowEntries(DeviceId d);
    void apply(FlowRuleOperations o);
    void removeFlowRulesById(ApplicationId app);
}
