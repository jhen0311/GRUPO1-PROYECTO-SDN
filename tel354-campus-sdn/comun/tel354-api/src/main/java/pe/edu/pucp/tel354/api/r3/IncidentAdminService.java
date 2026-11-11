package pe.edu.pucp.tel354.api.r3;

import java.time.Duration;
import java.util.List;

/**
 * Operaciones de TI sobre los incidentes (implementa M5; las usan la API REST y los comandos
 * tel354:*). Cualquier instancia del clúster acepta la operación.
 */
public interface IncidentAdminService {

    List<Incidente> listar(Filtro filtro);

    /** Incidente con su evidencia, o null. */
    Incidente detalle(long incidenteId);

    /**
     * Retira la contención, inserta la sanción en cuarentena, marca accion = N3, publica
     * QUARANTINE_CONFIRMED y pide a M1 el paso forzado (en ese orden).
     *
     * @param via "consola" o "rest"
     */
    Incidente confirmar(long incidenteId, Duration sancion, String solicitante, String motivo, String via);

    /** Retira la contención, registra mitigado_en y publica CONTAINMENT_LIFTED. */
    Incidente descartar(long incidenteId, String solicitante, String motivo, String via);

    void modo(Modo modo, String solicitante, String via);

    Modo modo();
}
