package pe.edu.pucp.tel354.api;

import org.onlab.packet.Ip4Address;
import org.onlab.packet.Ip4Prefix;
import org.onlab.packet.MacAddress;

/**
 * Constantes del pipeline OpenFlow del switch de acceso y del plan de direccionamiento
 * (AVZ07, Tablas 11 a 13; LLSD R3, Tablas 10 y 11; LAB6, Tabla 1).
 */
public final class Pipeline {

    // Tablas del switch de acceso
    public static final int T_ADMISION = 0;
    public static final int T_AUTORIZACION = 1;
    public static final int T_ENTREGA = 2;

    // Tabla 0 (admisión, R1)
    public static final int P_EAPOL = 900;
    public static final int P_ARP = 800;
    public static final int P_UPLINK = 600;
    public static final int P_DHCP = 525;
    public static final int P_BINDING = 520;
    public static final int P_OTRA_IP = 505;
    public static final int P_DEFECTO_T0 = 10;

    // Tabla 1 (autorización, R2 y R3)
    public static final int P_N2_DNS = 302;
    public static final int P_N2_AISLA = 301;
    public static final int P_CONTENCION = 300;
    public static final int P_DENEGADO = 150;
    public static final int P_LIMITE_R4 = 120;
    public static final int P_CURSO = 110;
    public static final int P_PERMISO = 100;
    public static final int P_DNS = 60;
    public static final int P_CUARENTENA_AISLA = 58;
    public static final int P_CUARENTENA = 57;
    public static final int P_CAPTURA = 55;
    public static final int P_SALIDA = 50;

    // Tabla 2 (entrega)
    public static final int P_HOST_IP = 200;
    public static final int P_HOST_MAC = 190;
    public static final int P_SALIDA_T2 = 180;

    /** hard_timeout del DROP 150 de M2. */
    public static final int HARD_DENEGADO_SEG = 8;

    public static final int VLAN_CUARENTENA = 999;
    public static final int K_CUARENTENA = 7;

    public static final Ip4Address DNS = Ip4Address.valueOf("10.200.0.14");
    public static final Ip4Prefix RED_CAMPUS = Ip4Prefix.valueOf("10.0.0.0/8");
    public static final Ip4Prefix RED_SERVIDORES = Ip4Prefix.valueOf("10.200.0.0/16");
    public static final Ip4Prefix RED_CONTROL = Ip4Prefix.valueOf("172.16.0.0/16");

    private Pipeline() {
    }

    /** VLAN del pabellón N (100 + N). */
    public static int vlanPabellon(int pabellon) {
        return 100 + pabellon;
    }

    /** Segundo octeto base del rol k (b = 16·k). */
    public static int octeto(int k) {
        return 16 * k;
    }

    /** Gateway virtual del rol: 10.b.0.1. */
    public static Ip4Address gateway(int octeto) {
        return Ip4Address.valueOf("10." + octeto + ".0.1");
    }

    /** MAC del gateway virtual del rol: 02:00:00:00:00:‹b› (02:00:00:00:00:k0). */
    public static MacAddress macGateway(int octeto) {
        return MacAddress.valueOf(String.format("02:00:00:00:00:%02x", octeto & 0xff));
    }

    /** true si la IP es el gateway virtual de algún rol (10.16.0.1 ... 10.112.0.1). */
    public static boolean esGateway(Ip4Address ip) {
        byte[] b = ip.toOctets();
        int o2 = b[1] & 0xff;
        return (b[0] & 0xff) == 10 && o2 % 16 == 0 && o2 >= 16 && o2 <= 112 && b[2] == 0 && b[3] == 1;
    }

    /**
     * Suplantación inequívoca (LLSD R3, sección 5.1): la IP anunciada es un gateway virtual,
     * un servidor o una dirección del plano de control.
     */
    public static boolean esInequivoca(Ip4Address ip) {
        return esGateway(ip) || RED_SERVIDORES.contains(ip) || RED_CONTROL.contains(ip);
    }
}
