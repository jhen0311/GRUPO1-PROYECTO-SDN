package pe.edu.pucp.tel354.api.r3;

import pe.edu.pucp.tel354.api.sesion.SesionInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sujeto de ThreatEvent (LLSD R3, Tabla 9): sesión, métrica, valor, umbral, ventana, destinos
 * con sus puertos, instantes de la primera evidencia y del umbral.
 */
public final class Sospecha {

    private final SesionInfo sesion;
    private final Metrica metrica;
    private final TipoAtaque tipo;
    private final int valor;
    private final int umbral;
    private final int ventanaSeg;
    private final List<Destino> destinos;
    private final long tsPrimeraMs;
    private final long tsUmbralMs;
    private final boolean inequivoca;
    private final String detalle;

    private Sospecha(Builder b) {
        this.sesion = b.sesion;
        this.metrica = b.metrica;
        this.tipo = b.tipo != null ? b.tipo : b.metrica.tipoAtaque();
        this.valor = b.valor;
        this.umbral = b.umbral;
        this.ventanaSeg = b.ventanaSeg;
        this.destinos = Collections.unmodifiableList(new ArrayList<>(b.destinos));
        this.tsPrimeraMs = b.tsPrimeraMs;
        this.tsUmbralMs = b.tsUmbralMs > 0 ? b.tsUmbralMs : System.currentTimeMillis();
        this.inequivoca = b.inequivoca;
        this.detalle = b.detalle;
    }

    public static Builder builder(SesionInfo sesion, Metrica metrica) {
        return new Builder(sesion, metrica);
    }

    public SesionInfo sesion() {
        return sesion;
    }

    public Metrica metrica() {
        return metrica;
    }

    public TipoAtaque tipo() {
        return tipo;
    }

    public int valor() {
        return valor;
    }

    public int umbral() {
        return umbral;
    }

    public int ventanaSeg() {
        return ventanaSeg;
    }

    public List<Destino> destinos() {
        return destinos;
    }

    public long tsPrimeraMs() {
        return tsPrimeraMs;
    }

    public long tsUmbralMs() {
        return tsUmbralMs;
    }

    /** Suplantación de un gateway, un servidor o el plano de control. */
    public boolean inequivoca() {
        return inequivoca;
    }

    /** Texto libre (firma de Suricata, origen de una prueba). */
    public String detalle() {
        return detalle;
    }

    @Override
    public String toString() {
        return metrica + "=" + valor + "/" + umbral + " W=" + ventanaSeg + "s sesion " + sesion.id() + " -> " + destinos
                + (inequivoca ? " inequívoca" : "");
    }

    public static final class Builder {
        private final SesionInfo sesion;
        private final Metrica metrica;
        private TipoAtaque tipo;
        private int valor;
        private int umbral;
        private int ventanaSeg;
        private final List<Destino> destinos = new ArrayList<>();
        private long tsPrimeraMs;
        private long tsUmbralMs;
        private boolean inequivoca;
        private String detalle;

        private Builder(SesionInfo sesion, Metrica metrica) {
            this.sesion = sesion;
            this.metrica = metrica;
        }

        public Builder tipo(TipoAtaque v) {
            tipo = v;
            return this;
        }

        public Builder valor(int v) {
            valor = v;
            return this;
        }

        public Builder umbral(int v) {
            umbral = v;
            return this;
        }

        public Builder ventanaSeg(int v) {
            ventanaSeg = v;
            return this;
        }

        public Builder destino(Destino d) {
            destinos.add(d);
            return this;
        }

        public Builder destinos(List<Destino> l) {
            destinos.addAll(l);
            return this;
        }

        public Builder tsPrimeraMs(long v) {
            tsPrimeraMs = v;
            return this;
        }

        public Builder tsUmbralMs(long v) {
            tsUmbralMs = v;
            return this;
        }

        public Builder inequivoca(boolean v) {
            inequivoca = v;
            return this;
        }

        public Builder detalle(String v) {
            detalle = v;
            return this;
        }

        public Sospecha build() {
            return new Sospecha(this);
        }
    }
}
