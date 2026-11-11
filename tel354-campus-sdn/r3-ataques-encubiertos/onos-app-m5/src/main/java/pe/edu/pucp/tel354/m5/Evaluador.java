package pe.edu.pucp.tel354.m5;

import pe.edu.pucp.tel354.api.r3.Criticidad;
import pe.edu.pucp.tel354.api.r3.Nivel;
import pe.edu.pucp.tel354.api.r3.NivelAtaque;
import pe.edu.pucp.tel354.api.r3.TipoAtaque;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluación de M5 (LLSD R3, sección 5.6): nivel de ataque (Tabla 15), matriz de contención
 * (Tabla 16), ajustes por reincidencia, destino único y ataque coordinado sobre un puerto de
 * servicio, y confirmación automática. Sin estado ni dependencias de ONOS.
 */
public final class Evaluador {

    private Evaluador() {
    }

    /** Datos de entrada que no vienen de la sospecha. */
    public static final class Contexto {
        /** Incidentes anteriores del usuario en las últimas 24 h (sin contar el actual). */
        public int previos24h;
        /** De ellos, los de nivel Fuerte. */
        public int fuertesPrevios24h;
        /** Hay contenciones anteriores del usuario aún vigentes. */
        public boolean contencionesVigentes;
        /** Ataque coordinado en que los orígenes solo intentaron el puerto de servicio de un recurso. */
        public boolean coordinadoEnPuertoServicio;
    }

    public static final class Resultado {
        public NivelAtaque nivelAtaque;
        public Criticidad criticidad;
        public Nivel nivel;
        public boolean revisionPrioritaria;
        public boolean confirmarAutomatico;
        public int reincidencia;
        public final List<String> ajustes = new ArrayList<>();

        @Override
        public String toString() {
            return nivelAtaque + "/" + criticidad + " -> " + nivel + (revisionPrioritaria ? " + revisión prioritaria" : "")
                    + (confirmarAutomatico ? " + confirmación automática" : "") + " " + ajustes;
        }
    }

    /** Tabla 15. */
    public static NivelAtaque nivelAtaque(TipoAtaque t, boolean coordinadoEnPuertoServicio) {
        switch (t) {
            case ESCANEO_PUERTOS:
            case ESCANEO_RED:
            case ACCESO_REITERADO:
                return NivelAtaque.Bajo;
            case ATAQUE_COORDINADO:
                return coordinadoEnPuertoServicio ? NivelAtaque.Bajo : NivelAtaque.Intermedio;
            case SUPLANTACION:
            case PETICION_MALICIOSA:
                return NivelAtaque.Fuerte;
            default:                                   // FUERZA_BRUTA_SERVICIO, FIRMA_MALICIOSA
                return NivelAtaque.Intermedio;
        }
    }

    /** Tabla 16. */
    public static Nivel matriz(NivelAtaque a, Criticidad c) {
        switch (a) {
            case Bajo:
                return c == Criticidad.MENOR ? Nivel.N0 : Nivel.N1;
            case Intermedio:
                return c == Criticidad.CRITICO ? Nivel.N2 : Nivel.N1;
            default:
                return Nivel.N2;
        }
    }

    /**
     * @param destinoUnico la sospecha tiene un solo destino (N1 necesita uno; si no, N2)
     * @param inequivoca   suplantación de un gateway, un servidor o el plano de control
     */
    public static Resultado evaluar(TipoAtaque tipo, Criticidad criticidad, boolean destinoUnico, boolean inequivoca,
                                    Contexto ctx) {
        Resultado r = new Resultado();
        r.criticidad = criticidad;
        r.nivelAtaque = nivelAtaque(tipo, ctx.coordinadoEnPuertoServicio);
        if (tipo == TipoAtaque.ATAQUE_COORDINADO && ctx.coordinadoEnPuertoServicio) {
            r.ajustes.add("coordinado en puerto de servicio: Bajo");
        }
        r.nivel = matriz(r.nivelAtaque, criticidad);
        r.revisionPrioritaria = r.nivelAtaque == NivelAtaque.Fuerte && criticidad != Criticidad.MENOR;
        r.reincidencia = ctx.previos24h + 1;
        if (ctx.previos24h >= 1 && r.nivel != Nivel.N2) {
            r.nivel = r.nivel.subir();
            r.ajustes.add("reincidencia en 24 h: +1 nivel");
        }
        if (r.nivel == Nivel.N1 && !destinoUnico) {
            r.nivel = Nivel.N2;
            r.ajustes.add("sin destino único: N2");
        }
        boolean reincidenciaGrave = r.nivelAtaque == NivelAtaque.Fuerte && ctx.fuertesPrevios24h >= 2
                && !ctx.contencionesVigentes;
        r.confirmarAutomatico = inequivoca || reincidenciaGrave;
        if (inequivoca) {
            r.ajustes.add("evidencia inequívoca: confirmación automática");
        } else if (reincidenciaGrave) {
            r.ajustes.add("tercer incidente Fuerte en 24 h: confirmación automática");
        }
        return r;
    }
}
