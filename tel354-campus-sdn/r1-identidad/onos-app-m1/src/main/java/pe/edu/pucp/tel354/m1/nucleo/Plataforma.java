package pe.edu.pucp.tel354.m1.nucleo;

import java.util.List;

/**
 * Lo que el núcleo de M1 necesita del controlador. En ONOS lo implementan PacketService
 * (Packet-Out), FlowRuleService (reglas) y DeviceAdminService (rebote del puerto);
 * en el banco de pruebas, un emulador del switch.
 */
public interface Plataforma {

    /** Packet-Out de una trama Ethernet completa por un puerto del switch. */
    void enviarTrama(String dpid, int puerto, byte[] trama);

    /**
     * Reglas 525 y 505 de la Tabla 0 (fase 2). Devuelve los objetos instalados.
     * Las reglas de {@code retirarAntes} se retiran en una etapa previa: si una regla nueva es
     * igual a una vieja (mismo puerto y MAC), el borrado no puede alcanzar a la nueva.
     */
    List<Object> instalarAutenticacion(Sesion s, List<Object> retirarAntes);

    /** Reglas 520 (Tabla 0), 110 (Tabla 1) y 200/190 (Tabla 2) de la fase 3, con la misma etapa previa. */
    List<Object> instalarActivacion(Sesion s, List<AccesoCurso> cursos, List<Object> retirarAntes);

    /** Retira reglas instaladas antes (borrado en bloque de la sesión). */
    void retirar(List<Object> reglas);

    /** Baja y sube el puerto: el supplicant detecta el enlace y vuelve a autenticarse. */
    void rebotarPuerto(String dpid, int puerto);

    /** Servidor de un curso en el que el usuario está inscrito (vista v_acceso_curso). */
    final class AccesoCurso {
        public final String curso;
        public final String ip;
        public final String protocolo;   // TCP o UDP
        public final int puerto;

        public AccesoCurso(String curso, String ip, String protocolo, int puerto) {
            this.curso = curso;
            this.ip = ip;
            this.protocolo = protocolo;
            this.puerto = puerto;
        }
    }
}
