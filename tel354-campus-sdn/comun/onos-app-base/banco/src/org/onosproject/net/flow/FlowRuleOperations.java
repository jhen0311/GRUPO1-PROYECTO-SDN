// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.flow;

import java.util.ArrayList;
import java.util.List;

/** Lote de operaciones en etapas: cada elemento es {"add" | "remove", regla}. */
public final class FlowRuleOperations {
    public final List<List<Object[]>> etapas = new ArrayList<>();
    public FlowRuleOperationsContext contexto;

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final FlowRuleOperations o = new FlowRuleOperations();
        private List<Object[]> actual = new ArrayList<>();
        public Builder add(FlowRule r) { actual.add(new Object[] {"add", r}); return this; }
        public Builder remove(FlowRule r) { actual.add(new Object[] {"remove", r}); return this; }
        public Builder newStage() { o.etapas.add(actual); actual = new ArrayList<>(); return this; }
        public FlowRuleOperations build(FlowRuleOperationsContext c) { o.etapas.add(actual); o.contexto = c; return o; }
    }
}
