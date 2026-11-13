package pe.edu.pucp.tel354.m1.nucleo;

/** Regla de sesión que sigue en el switch: se usa para reconstruir la sesión al ganar la maestría. */
public final class ReglaRecuperada {
    public final Object regla;
    public final long sesionId;
    public final int indice;
    public final int restanteSeg;

    public ReglaRecuperada(Object regla, long sesionId, int indice, int restanteSeg) {
        this.regla = regla;
        this.sesionId = sesionId;
        this.indice = indice;
        this.restanteSeg = restanteSeg;
    }

    /** Índices 0 y 1 (525 y 505): fase 2. El resto: fase 3. */
    public boolean deAutenticacion() {
        return indice <= 1;
    }
}
