package pe.edu.pucp.tel354.m5;

import pe.edu.pucp.tel354.api.r3.Sospecha;

/**
 * Entradas de prueba de M5 (comandos tel354:r3-simular*): permiten verificar M5 antes de que
 * M4a y M3 estén instalados, inyectando el mismo evento que publicarían.
 */
public interface M5Pruebas {

    /** Procesa la sospecha como si M4a hubiera publicado THREAT_SUSPECTED. */
    void simular(Sospecha s);

    /** Procesa un COUNTER_DELTA de la regla 301 del incidente, como si lo publicara M3. */
    boolean simularContador(long incidenteId);
}
