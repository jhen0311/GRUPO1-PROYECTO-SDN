// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.meter;

public final class DefaultBand implements Band {
    private long rate;
    private long burst;
    public static Builder builder() { return new Builder(); }
    public long rate() { return rate; }
    public long burst() { return burst; }

    public static final class Builder {
        private final DefaultBand b = new DefaultBand();
        public Builder ofType(Band.Type t) { return this; }
        public Builder withRate(long r) { b.rate = r; return this; }
        public Builder burstSize(long x) { b.burst = x; return this; }
        public Band build() { return b; }
    }
}
