package pe.edu.pucp.tel354.m1.nucleo;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Verificación de las respuestas RADIUS sobre los bytes recibidos (RFC 2865 y RFC 3579).
 * La clase RADIUS de ONOS calcula el Message-Authenticator con el autenticador que trae el
 * paquete, lo que solo vale para las solicitudes; en una respuesta ambos campos se calculan
 * con el Request Authenticator de la solicitud.
 */
public final class Radius {

    public static final byte ATTR_USER_NAME = 1;
    public static final byte ATTR_NAS_IP = 4;
    public static final byte ATTR_NAS_PORT = 5;
    public static final byte ATTR_FILTER_ID = 11;
    public static final byte ATTR_FRAMED_MTU = 12;
    public static final byte ATTR_STATE = 24;
    public static final byte ATTR_VSA = 26;
    public static final byte ATTR_SESSION_TIMEOUT = 27;
    public static final byte ATTR_CALLED_STATION_ID = 30;
    public static final byte ATTR_CALLING_STATION_ID = 31;
    public static final byte ATTR_NAS_IDENTIFIER = 32;
    public static final byte ATTR_NAS_PORT_TYPE = 61;
    public static final byte ATTR_EAP_MESSAGE = 79;
    public static final byte ATTR_MESSAGE_AUTH = 80;
    public static final byte ATTR_NAS_PORT_ID = 87;

    /** Número de empresa provisional de la demo (dictionary.pucp). */
    public static final int VENDOR_PUCP = 99999;
    public static final int VSA_ROLE_CODE = 1;
    public static final int VSA_MAX_SESSIONS = 2;

    private Radius() {
    }

    /** Response Authenticator = MD5(Code | Id | Length | Request Authenticator | Atributos | Secreto). */
    public static boolean respuestaValida(byte[] pkt, int len, byte[] reqAuth, String secreto) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(pkt, 0, 4);
            md5.update(reqAuth);
            md5.update(pkt, 20, len - 20);
            md5.update(secreto.getBytes(StandardCharsets.UTF_8));
            byte[] esperado = md5.digest();
            return Arrays.equals(esperado, Arrays.copyOfRange(pkt, 4, 20));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Message-Authenticator de una respuesta: HMAC-MD5 con el Request Authenticator en el campo
     * del autenticador y el propio atributo en ceros. Si la respuesta no lo trae, devuelve false.
     */
    public static boolean messageAuthenticatorValido(byte[] pkt, int len, byte[] reqAuth, String secreto) {
        byte[] copia = Arrays.copyOf(pkt, len);
        System.arraycopy(reqAuth, 0, copia, 4, 16);
        byte[] recibido = null;
        int i = 20;
        while (i + 2 <= len) {
            int tipo = copia[i] & 0xff;
            int l = copia[i + 1] & 0xff;
            if (l < 2 || i + l > len) {
                return false;
            }
            if (tipo == (ATTR_MESSAGE_AUTH & 0xff) && l == 18) {
                recibido = Arrays.copyOfRange(copia, i + 2, i + 18);
                Arrays.fill(copia, i + 2, i + 18, (byte) 0);
            }
            i += l;
        }
        if (recibido == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacMD5");
            mac.init(new SecretKeySpec(secreto.getBytes(StandardCharsets.UTF_8), "HmacMD5"));
            return Arrays.equals(mac.doFinal(copia), recibido);
        } catch (Exception e) {
            return false;
        }
    }

    public static byte[] entero(int v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    public static int aEntero(byte[] b) {
        if (b == null || b.length != 4) {
            return -1;
        }
        return ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
    }

    public static byte[] texto(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Valor entero de un VSA de PUCP dentro de un atributo 26 (o -1 si no corresponde). */
    public static int vsaPucp(byte[] valor26, int tipoVsa) {
        if (valor26 == null || valor26.length < 6) {
            return -1;
        }
        int vendor = aEntero(Arrays.copyOfRange(valor26, 0, 4));
        if (vendor != VENDOR_PUCP) {
            return -1;
        }
        int i = 4;
        while (i + 2 <= valor26.length) {
            int t = valor26[i] & 0xff;
            int l = valor26[i + 1] & 0xff;
            if (l < 2 || i + l > valor26.length) {
                return -1;
            }
            if (t == tipoVsa && l == 6) {
                return aEntero(Arrays.copyOfRange(valor26, i + 2, i + 6));
            }
            i += l;
        }
        return -1;
    }
}
