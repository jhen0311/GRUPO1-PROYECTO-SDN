// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.flow.instructions;

import org.onosproject.net.PortNumber;
import org.onosproject.net.meter.MeterId;

public final class Instructions {
    private Instructions() { }

    public static final class MeterInstruction implements Instruction {
        private final MeterId m;
        public MeterInstruction(MeterId m) { this.m = m; }
        public MeterId meterId() { return m; }
        @Override public boolean equals(Object o) { return o instanceof MeterInstruction && ((MeterInstruction) o).m.equals(m); }
        @Override public int hashCode() { return m.hashCode(); }
        @Override public String toString() { return "meter:" + m; }
    }

    public static final class OutputInstruction implements Instruction {
        private final PortNumber p;
        public OutputInstruction(PortNumber p) { this.p = p; }
        public PortNumber port() { return p; }
        @Override public boolean equals(Object o) { return o instanceof OutputInstruction && ((OutputInstruction) o).p.equals(p); }
        @Override public int hashCode() { return p.hashCode(); }
        @Override public String toString() { return "output:" + p; }
    }

    /** Cualquier otra acción (push/pop VLAN, set VLAN, set MAC), identificada por su texto. */
    public static final class Otra implements Instruction {
        private final String t;
        public Otra(String t) { this.t = t; }
        @Override public boolean equals(Object o) { return o instanceof Otra && ((Otra) o).t.equals(t); }
        @Override public int hashCode() { return t.hashCode(); }
        @Override public String toString() { return t; }
    }
}
