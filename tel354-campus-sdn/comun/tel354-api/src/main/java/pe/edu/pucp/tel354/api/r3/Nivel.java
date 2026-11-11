package pe.edu.pucp.tel354.api.r3;

/** Nivel de contención (incidente.accion). */
public enum Nivel {
    /** Solo registro. */
    N0,
    /** Bloqueo puntual hacia el destino (regla 300, 900 s). */
    N1,
    /** Aislamiento: solo DNS e Internet a 1 Mbps (reglas 300-302, 3 600 s). */
    N2,
    /** Cuarentena confirmada por TI o automática. */
    N3;

    /** Entradas de flujo por sesión en el switch de acceso: e(N1) = 1, e(N2) = 4. */
    public int entradas() {
        return this == N1 ? 1 : this == N2 ? 4 : 0;
    }

    public Nivel subir() {
        return this == N0 ? N1 : N2;
    }
}
