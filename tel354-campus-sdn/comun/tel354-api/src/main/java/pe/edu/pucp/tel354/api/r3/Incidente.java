package pe.edu.pucp.tel354.api.r3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Vista de un incidente de R3 (fila de incidente con su estado derivado). */
public final class Incidente {

    private final long id;
    private final long sesionId;
    private final int usuarioId;
    private final String usuario;
    private final TipoAtaque tipo;
    private final NivelAtaque nivelAtaque;
    private final Criticidad criticidad;
    private final Nivel nivel;
    private final Nivel nivelCalculado;
    private final EstadoIncidente estado;
    private final boolean revisionPrioritaria;
    private final long detectadoMs;
    private final long expiraMs;
    private final long mitigadoMs;
    private final List<Long> sesionesContenidas;
    private final String evidencia;

    private Incidente(Builder b) {
        id = b.id;
        sesionId = b.sesionId;
        usuarioId = b.usuarioId;
        usuario = b.usuario;
        tipo = b.tipo;
        nivelAtaque = b.nivelAtaque;
        criticidad = b.criticidad;
        nivel = b.nivel;
        nivelCalculado = b.nivelCalculado;
        estado = b.estado;
        revisionPrioritaria = b.revisionPrioritaria;
        detectadoMs = b.detectadoMs;
        expiraMs = b.expiraMs;
        mitigadoMs = b.mitigadoMs;
        sesionesContenidas = Collections.unmodifiableList(new ArrayList<>(b.sesiones));
        evidencia = b.evidencia;
    }

    public static Builder builder() {
        return new Builder();
    }

    public long id() {
        return id;
    }

    public long sesionId() {
        return sesionId;
    }

    public int usuarioId() {
        return usuarioId;
    }

    public String usuario() {
        return usuario;
    }

    public TipoAtaque tipo() {
        return tipo;
    }

    public NivelAtaque nivelAtaque() {
        return nivelAtaque;
    }

    public Criticidad criticidad() {
        return criticidad;
    }

    /** Nivel aplicado (incidente.accion). */
    public Nivel nivel() {
        return nivel;
    }

    /** Nivel que resultó de la evaluación (en OBSERVACION difiere del aplicado). */
    public Nivel nivelCalculado() {
        return nivelCalculado;
    }

    public EstadoIncidente estado() {
        return estado;
    }

    public boolean revisionPrioritaria() {
        return revisionPrioritaria;
    }

    public long detectadoMs() {
        return detectadoMs;
    }

    /** 0 si no hay contención vigente. */
    public long expiraMs() {
        return expiraMs;
    }

    public long mitigadoMs() {
        return mitigadoMs;
    }

    public List<Long> sesionesContenidas() {
        return sesionesContenidas;
    }

    /** Columna evidencia (JSON). */
    public String evidencia() {
        return evidencia;
    }

    @Override
    public String toString() {
        return "incidente " + id + " [" + usuario + " " + tipo + " " + nivelAtaque + "/" + criticidad + " -> " + nivel
                + " " + estado + "]";
    }

    public static final class Builder {
        private long id;
        private long sesionId;
        private int usuarioId;
        private String usuario;
        private TipoAtaque tipo;
        private NivelAtaque nivelAtaque;
        private Criticidad criticidad;
        private Nivel nivel = Nivel.N0;
        private Nivel nivelCalculado = Nivel.N0;
        private EstadoIncidente estado = EstadoIncidente.EN_INVESTIGACION;
        private boolean revisionPrioritaria;
        private long detectadoMs;
        private long expiraMs;
        private long mitigadoMs;
        private final List<Long> sesiones = new ArrayList<>();
        private String evidencia = "{}";

        public Builder id(long v) {
            id = v;
            return this;
        }

        public Builder sesionId(long v) {
            sesionId = v;
            return this;
        }

        public Builder usuarioId(int v) {
            usuarioId = v;
            return this;
        }

        public Builder usuario(String v) {
            usuario = v;
            return this;
        }

        public Builder tipo(TipoAtaque v) {
            tipo = v;
            return this;
        }

        public Builder nivelAtaque(NivelAtaque v) {
            nivelAtaque = v;
            return this;
        }

        public Builder criticidad(Criticidad v) {
            criticidad = v;
            return this;
        }

        public Builder nivel(Nivel v) {
            nivel = v;
            return this;
        }

        public Builder nivelCalculado(Nivel v) {
            nivelCalculado = v;
            return this;
        }

        public Builder estado(EstadoIncidente v) {
            estado = v;
            return this;
        }

        public Builder revisionPrioritaria(boolean v) {
            revisionPrioritaria = v;
            return this;
        }

        public Builder detectadoMs(long v) {
            detectadoMs = v;
            return this;
        }

        public Builder expiraMs(long v) {
            expiraMs = v;
            return this;
        }

        public Builder mitigadoMs(long v) {
            mitigadoMs = v;
            return this;
        }

        public Builder sesiones(List<Long> v) {
            sesiones.addAll(v);
            return this;
        }

        public Builder evidencia(String v) {
            evidencia = v;
            return this;
        }

        public Incidente build() {
            return new Incidente(this);
        }
    }
}
