package pe.edu.pucp.tel354.api.r3;

/**
 * Entrada de las alertas del IDS Suricata (implementa M4a; LLSD R3, Tabla de eventos, IdsAlert).
 * La API REST de M5 (POST /onos/campus/v1/ids/alertas) la invoca con la alerta ya normalizada
 * por el adaptador de la VM-IDS.
 */
public interface IdsAlertService {

    /** Resultado de procesar una alerta. */
    enum Resultado {
        /** Atribuida a una sesión y encolada en M4a. */
        ACEPTADA,
        /** La IP de origen no tiene sesión abierta (por ejemplo, un servidor): no se atribuye. */
        SIN_SESION,
        /** El sid y la categoría no tienen un tipo de ataque asignado en la network config. */
        NO_MAPEADA,
        /** Dirección IP ilegible o campos incompletos. */
        INVALIDA
    }

    /**
     * @param srcIp     IP del atacante (origen del paquete que disparó la firma)
     * @param dstIp     IP del servicio atacado
     * @param proto     número de protocolo IP (6 TCP, 17 UDP, 1 ICMP)
     * @param puerto    puerto de destino (0 si no aplica)
     * @param sid       signature_id de Suricata
     * @param categoria alert.category de Suricata (texto de la clasificación)
     * @param firma     alert.signature
     * @param severidad alert.severity de Suricata (1 = la más alta)
     * @param sensor    interfaz o espejo que vio el tráfico (informativo)
     */
    Resultado alerta(String srcIp, String dstIp, int proto, int puerto, int sid,
                     String categoria, String firma, int severidad, String sensor);
}
