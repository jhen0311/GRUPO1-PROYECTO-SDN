package pe.edu.pucp.tel354.m1.nucleo;

import java.util.ArrayList;
import java.util.List;

/**
 * Sesión de un equipo en un puerto de acceso (AVZ07, sección 6).
 * AUTENTICADA: hay Access-Accept y reglas 525/505; ACTIVA: hay IP y reglas 520, 110, 200 y 190.
 */
public class Sesion {
    public enum Estado { AUTENTICADA, ACTIVA, CERRADA }

    public long id;               // sesion.id en PostgreSQL (base de la cookie 0x02)
    public String dpid;           // of:0000000000000001
    public int puerto;
    public String mac;            // aa:bb:cc:dd:ee:ff (minúsculas)
    public String usuario;        // identidad interna (uid_ldap)
    public int usuarioId;
    public String rol;            // Filter-Id
    public int rolId;
    public int octeto;            // b = 16·k
    public int pabellon;          // N
    public int vlan;              // 100 + N, o 999 en cuarentena
    public int timeoutSeg;        // Session-Timeout
    public long inicioMs = System.currentTimeMillis();
    public int dispositivoId;
    public String ip;             // null hasta el DHCPACK
    public Estado estado = Estado.AUTENTICADA;

    /** Reglas instaladas (objetos del programador: FlowRule en ONOS). */
    public final List<Object> reglasAutenticacion = new ArrayList<>();
    public final List<Object> reglasActivacion = new ArrayList<>();

    public int codigoRol() {
        return octeto / 16;
    }

    /** Segundos que le quedan a la sesión (mínimo 1). */
    public int restanteSeg() {
        long usados = (System.currentTimeMillis() - inicioMs) / 1000;
        return (int) Math.max(1, timeoutSeg - usados);
    }

    public String clave() {
        return clave(dpid, puerto, mac);
    }

    public static String clave(String dpid, int puerto, String mac) {
        return dpid + "/" + puerto + "/" + mac.toLowerCase();
    }

    @Override
    public String toString() {
        return "sesion " + id + " [" + usuario + " " + rol + " " + mac + " en " + dpid + "/" + puerto
                + (ip != null ? " IP " + ip : "") + " VLAN " + vlan + " " + estado + "]";
    }
}
