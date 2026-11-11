package pe.edu.pucp.tel354.api.r3;

/** Estado derivado del incidente (LLSD R3, sección 6.3). */
public enum EstadoIncidente {
    /** Abierto: contención vigente o pendiente de revisión. */
    EN_INVESTIGACION,
    /** TI (o la regla automática) lo confirmó: hay fila en cuarentena. */
    CONFIRMADO,
    /** TI lo descartó. */
    DESCARTADO,
    /** La contención venció sin renovación. */
    VENCIDO
}
