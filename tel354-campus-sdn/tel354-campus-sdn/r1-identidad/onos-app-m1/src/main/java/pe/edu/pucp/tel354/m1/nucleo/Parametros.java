package pe.edu.pucp.tel354.m1.nucleo;

/**
 * Parámetros de M1 (AVZ07, Tabla 2). Los valores por defecto son los del diseño;
 * en ONOS se sobrescriben con la network config de la app (m1-netcfg.json).
 */
public class Parametros {
    /** Dirección de ONOS en el plano de control: NAS-IP de RADIUS y origen de los mensajes. */
    public String nasIp = "172.16.0.10";
    public String radiusIp = "10.200.0.10";
    public int radiusPuerto = 1812;
    public String radiusSecreto = "tel354-secreto";
    /** Segundos sin respuesta antes de retransmitir un Access-Request, y número de reintentos. */
    public int radiusEsperaSeg = 3;
    public int radiusReintentos = 2;
    /** MTU que se anuncia a FreeRADIUS (Framed-MTU): limita el tamaño de los fragmentos TLS. */
    public int framedMtu = 1400;

    /** Relay DHCP: giaddr y origen de los mensajes hacia Kea (puerto 67). */
    public String relayIp = "172.16.0.10";
    public String keaIp = "10.200.0.13";

    /** PostgreSQL (usuario onos_m1). TLS con la CA de la demo (verify-ca). */
    public String jdbcUrl = "jdbc:postgresql://10.200.0.12:5432/campus"
            + "?ssl=true&sslmode=verify-ca&sslrootcert=/etc/tel354/certs/ca.pem";
    public String jdbcUsuario = "onos_m1";
    public String jdbcClave = "m1-demo";

    /** MAC con la que el autenticador firma las tramas EAPOL hacia el host. */
    public String macAutenticador = "02:00:00:00:00:01";

    /** Rebotar el puerto del host cuando su sesión expira (fuerza la reautenticación). */
    public boolean rebotarPuertoAlExpirar = true;
}
