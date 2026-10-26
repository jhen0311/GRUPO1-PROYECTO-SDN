package pe.edu.pucp.tel354.m1.nucleo;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.util.Arrays;

/**
 * Lectura y reescritura de mensajes DHCPv4 sobre los bytes (RFC 2131 y RFC 3046).
 * Se trabaja con los bytes y no con la clase DHCP de ONOS para que el relay no altere
 * opciones que no entiende.
 */
public final class Dhcp {

    public static final int DISCOVER = 1, OFFER = 2, REQUEST = 3, DECLINE = 4,
            ACK = 5, NAK = 6, RELEASE = 7, INFORM = 8;
    static final int OPCIONES = 240;                 // 236 de cabecera + 4 de magic cookie
    static final byte[] COOKIE = {99, (byte) 130, 83, 99};
    static final int OPT_RELAY = 82, OPT_TIPO = 53, OPT_FIN = 255, OPT_PAD = 0;

    /** Campos que usa el relay. */
    public static final class Mensaje {
        public int op;
        public int xid;
        public int flags;
        public byte[] ciaddr = new byte[4];
        public byte[] yiaddr = new byte[4];
        public byte[] giaddr = new byte[4];
        public byte[] chaddr = new byte[6];
        public int tipo;                              // opción 53

        public boolean difusion() {
            return (flags & 0x8000) != 0;
        }

        public String macCliente() {
            StringBuilder sb = new StringBuilder();
            for (byte b : chaddr) {
                if (sb.length() > 0) {
                    sb.append(':');
                }
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        }
    }

    private Dhcp() {
    }

    /** Devuelve null si los bytes no son un mensaje DHCP válido. */
    public static Mensaje leer(byte[] d) {
        if (d.length < OPCIONES + 1 || !Arrays.equals(Arrays.copyOfRange(d, 236, 240), COOKIE)) {
            return null;
        }
        Mensaje m = new Mensaje();
        m.op = d[0] & 0xff;
        m.xid = ((d[4] & 0xff) << 24) | ((d[5] & 0xff) << 16) | ((d[6] & 0xff) << 8) | (d[7] & 0xff);
        m.flags = ((d[10] & 0xff) << 8) | (d[11] & 0xff);
        System.arraycopy(d, 12, m.ciaddr, 0, 4);
        System.arraycopy(d, 16, m.yiaddr, 0, 4);
        System.arraycopy(d, 24, m.giaddr, 0, 4);
        System.arraycopy(d, 28, m.chaddr, 0, 6);
        byte[] t = opcion(d, OPT_TIPO);
        m.tipo = (t != null && t.length == 1) ? t[0] & 0xff : 0;
        return m;
    }

    /** Valor de una opción, o null. */
    public static byte[] opcion(byte[] d, int codigo) {
        int i = OPCIONES;
        while (i < d.length) {
            int c = d[i] & 0xff;
            if (c == OPT_FIN) {
                break;
            }
            if (c == OPT_PAD) {
                i++;
                continue;
            }
            if (i + 1 >= d.length) {
                break;
            }
            int l = d[i + 1] & 0xff;
            if (i + 2 + l > d.length) {
                break;
            }
            if (c == codigo) {
                return Arrays.copyOfRange(d, i + 2, i + 2 + l);
            }
            i += 2 + l;
        }
        return null;
    }

    /**
     * Copia de las opciones sin la 82 (la de un cliente no es confiable y la del servidor no debe
     * llegar al host) y, si se indica, con una opción 82 nueva justo antes de END.
     */
    static byte[] reescribirOpciones(byte[] d, byte[] nueva82) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(d, 0, OPCIONES);
        int i = OPCIONES;
        while (i < d.length) {
            int c = d[i] & 0xff;
            if (c == OPT_FIN) {
                break;
            }
            if (c == OPT_PAD) {
                i++;
                continue;
            }
            if (i + 1 >= d.length) {
                break;
            }
            int l = d[i + 1] & 0xff;
            if (i + 2 + l > d.length) {
                break;
            }
            if (c != OPT_RELAY) {
                out.write(d, i, 2 + l);
            }
            i += 2 + l;
        }
        if (nueva82 != null) {
            out.write(OPT_RELAY);
            out.write(nueva82.length);
            out.write(nueva82, 0, nueva82.length);
        }
        out.write(OPT_FIN);
        while (out.size() < 300) {                         // longitud mínima de BOOTP
            out.write(OPT_PAD);
        }
        return out.toByteArray();
    }

    /** Mensaje del cliente listo para Kea: hops + 1, giaddr del relay y opción 82 (N, k). */
    public static byte[] haciaServidor(byte[] d, String relayIp, int pabellon, int codigoRol) throws Exception {
        byte[] sub = {1, 1, (byte) pabellon, 2, 1, (byte) codigoRol};   // Circuit-ID = N, Remote-ID = k
        byte[] r = reescribirOpciones(d, sub);
        r[3] = (byte) Math.min(255, (r[3] & 0xff) + 1);
        if ((r[24] | r[25] | r[26] | r[27]) == 0) {
            System.arraycopy(InetAddress.getByName(relayIp).getAddress(), 0, r, 24, 4);
        }
        return r;
    }

    /** Respuesta de Kea lista para el host: sin la opción 82. */
    public static byte[] haciaCliente(byte[] d) {
        return reescribirOpciones(d, null);
    }

    public static String ip(byte[] b) {
        return (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + "." + (b[3] & 0xff);
    }

    public static boolean ceros(byte[] b) {
        for (byte x : b) {
            if (x != 0) {
                return false;
            }
        }
        return true;
    }
}
