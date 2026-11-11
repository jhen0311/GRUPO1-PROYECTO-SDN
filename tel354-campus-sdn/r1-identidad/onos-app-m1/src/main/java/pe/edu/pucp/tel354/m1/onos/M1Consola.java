package pe.edu.pucp.tel354.m1.onos;

import pe.edu.pucp.tel354.api.sesion.SesionInfo;

import java.util.List;

/** Consultas de la consola de M1 (comandos tel354:sesiones y tel354:sesion). */
public interface M1Consola {

    /** Sesiones en memoria de esta instancia (switches de los que es master). */
    List<SesionInfo> sesionesLocales();

    /** Sesiones abiertas de todo el campus (PostgreSQL). */
    List<SesionInfo> sesionesAbiertas();

    /** Dirección in-band que usa esta instancia (NAS-IP y giaddr). */
    String nasIp();
}
