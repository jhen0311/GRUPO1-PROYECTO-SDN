package pe.edu.pucp.tel354.api.r3;

import org.onosproject.event.AbstractEvent;

/**
 * Eventos de M5 (LLSD R3, Tabla 9).
 * <ul>
 *   <li>CONTAINMENT_APPLIED: cambio de nivel N0-N2 (o renovación).</li>
 *   <li>CONTAINMENT_LIFTED: vencimiento sin renovación o descarte.</li>
 *   <li>QUARANTINE_CONFIRMED: sanción insertada en cuarentena ({@link #finSancionMs()}).</li>
 *   <li>ADMIN_OPERATION: operación de TI (para M6: operación, solicitante, vía, motivo).</li>
 * </ul>
 */
public class IncidentEvent extends AbstractEvent<IncidentEvent.Type, Incidente> {

    public enum Type { CONTAINMENT_APPLIED, CONTAINMENT_LIFTED, QUARANTINE_CONFIRMED, ADMIN_OPERATION }

    private final long finSancionMs;
    private final String solicitante;
    private final String motivo;
    private final String operacion;

    public IncidentEvent(Type tipo, Incidente incidente, long finSancionMs, String solicitante, String motivo,
                         String operacion) {
        super(tipo, incidente);
        this.finSancionMs = finSancionMs;
        this.solicitante = solicitante;
        this.motivo = motivo;
        this.operacion = operacion;
    }

    public IncidentEvent(Type tipo, Incidente incidente) {
        this(tipo, incidente, 0, null, null, null);
    }

    public long finSancionMs() {
        return finSancionMs;
    }

    public String solicitante() {
        return solicitante;
    }

    public String motivo() {
        return motivo;
    }

    /** confirmar, descartar o modo, con la vía (consola o REST). */
    public String operacion() {
        return operacion;
    }

    @Override
    public String toString() {
        return type() + " " + subject() + (operacion != null ? " " + operacion : "")
                + (solicitante != null ? " por " + solicitante : "");
    }
}
