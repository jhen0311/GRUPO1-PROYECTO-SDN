package pe.edu.pucp.tel354.api.r3;

/** Métricas de M4a (LLSD R3, sección 2.2) y su tipo de ataque (Tabla 15). */
public enum Metrica {
    /** Puertos distintos de un destino en la ventana. */
    P(TipoAtaque.ESCANEO_PUERTOS),
    /** Destinos distintos en la ventana (incluye ArpProbe). */
    D(TipoAtaque.ESCANEO_RED),
    /** Intentos denegados en W2. */
    N(TipoAtaque.ACCESO_REITERADO),
    /** Sesiones de origen distintas hacia un mismo destino en W2. */
    F(TipoAtaque.ATAQUE_COORDINADO),
    /** BindingViolation reenviada de inmediato. */
    SUPLANTACION(TipoAtaque.SUPLANTACION),
    /** Alerta de Suricata (fase posterior); el tipo concreto va en Sospecha.tipo(). */
    IDS(TipoAtaque.FIRMA_MALICIOSA);

    private final TipoAtaque tipo;

    Metrica(TipoAtaque tipo) {
        this.tipo = tipo;
    }

    public TipoAtaque tipoAtaque() {
        return tipo;
    }
}
