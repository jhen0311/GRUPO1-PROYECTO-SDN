package pe.edu.pucp.tel354.api.sesion;

/**
 * Motivos de cierre de una sesión (columna sesion.motivo_cierre).
 * Los que fuerzan la reautenticación rebotan el puerto del host.
 */
public enum Motivo {
    /** EAPOL-Logoff, enlace caído, reautenticación o 60 s sin DHCP. */
    DESCONEXION(false),
    /** Venció el Session-Timeout (hard_timeout de las reglas). */
    EXPIRACION(true),
    /** TI o un módulo revocó la sesión. */
    REVOCACION(true),
    /** El equipo apareció en otro puerto. */
    MOVILIDAD(false),
    /** El usuario superó su máximo de sesiones simultáneas. */
    LIMITE_SESIONES(false),
    /** Sanción N3 confirmada: el host vuelve con el rol Cuarentena. */
    CUARENTENA(true),
    /** El switch rechazó las reglas de la sesión (tabla llena). */
    TABLA_LLENA(false),
    /** M1 se desactivó o, al arrancar, la sesión ya no tenía reglas. */
    REINICIO(true);

    private final boolean rebota;

    Motivo(boolean rebota) {
        this.rebota = rebota;
    }

    /** true si el cierre rebota el puerto para forzar 802.1X otra vez. */
    public boolean rebota() {
        return rebota;
    }
}
