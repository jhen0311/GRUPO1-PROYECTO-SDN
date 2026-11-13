package pe.edu.pucp.tel354.m2;

import org.onlab.packet.Ip4Address;
import pe.edu.pucp.tel354.api.sesion.SesionInfo;

/**
 * Matriz de autorización en caché (pendiente de M2). Debe cargar v_permiso_efectivo (permisos por
 * rol, octeto b) y v_acceso_curso (servidores de cursos por usuario) con el rol onos_m2, y
 * recargarse con la notificación policy_changed de PostgreSQL. Mientras no esté implementada,
 * todo lo que llega por la captura 55 se trata como no autorizado: es lo correcto, porque la
 * regla 55 solo recibe lo que no coincidió con un permiso 100 o 110.
 */
public class Matriz {

    public boolean permite(SesionInfo s, Ip4Address destino, int proto, int puerto) {
        return false;
    }
}
