package pe.edu.pucp.tel354.api;

import org.onosproject.core.ApplicationId;

/**
 * Formato de la cookie OpenFlow de las apps del campus (LLSD R3, Tabla 7).
 *
 * <pre>
 *  bits 63-48  appId de la app dueña (ONOS asigna la regla a la app por estos bits)
 *  bits 47-40  tipo: 0x01 base, 0x02 sesión, 0x03 rol, 0x04 común, 0x05 camino,
 *              0x06 contención, 0x07 mitigación
 *  bits 39-8   identificador (sesion.id, recurso.id, incidente.id, ...)
 *  bits 7-0    índice de la regla dentro de su grupo
 * </pre>
 */
public final class Cookies {

    public static final int BASE = 0x01;
    public static final int SESION = 0x02;
    public static final int ROL = 0x03;
    public static final int COMUN = 0x04;
    public static final int CAMINO = 0x05;
    public static final int CONTENCION = 0x06;
    public static final int MITIGACION = 0x07;

    /** Índices de las reglas de sesión de M1 (tipo 0x02). Las 110 usan 5, 6, ... */
    public static final int IDX_525 = 0;
    public static final int IDX_505 = 1;
    public static final int IDX_520 = 2;
    public static final int IDX_200 = 3;
    public static final int IDX_190 = 4;
    public static final int IDX_110 = 5;

    /** Índices de contención de M5 (tipo 0x06): 0 = 300, 1 y 2 = 301, 3 = 302. */
    public static final int IDX_300 = 0;
    public static final int IDX_301_USUARIOS = 1;
    public static final int IDX_301_CONTROL = 2;
    public static final int IDX_302 = 3;

    private Cookies() {
    }

    public static long cookie(short appId, int tipo, long id, int indice) {
        return ((appId & 0xffffL) << 48) | ((tipo & 0xffL) << 40) | ((id & 0xffffffffL) << 8) | (indice & 0xffL);
    }

    public static long cookie(ApplicationId app, int tipo, long id, int indice) {
        return cookie(app.id(), tipo, id, indice);
    }

    public static short appId(long cookie) {
        return (short) (cookie >>> 48);
    }

    public static int tipo(long cookie) {
        return (int) ((cookie >>> 40) & 0xff);
    }

    public static long id(long cookie) {
        return (cookie >>> 8) & 0xffffffffL;
    }

    public static int indice(long cookie) {
        return (int) (cookie & 0xff);
    }

    /** true si la cookie es de la app y del tipo indicados. */
    public static boolean es(long cookie, ApplicationId app, int tipo) {
        return appId(cookie) == app.id() && tipo(cookie) == tipo;
    }

    public static String texto(long cookie) {
        return String.format("0x%016x (app %d, tipo 0x%02x, id %d, índice %d)", cookie,
                             appId(cookie), tipo(cookie), id(cookie), indice(cookie));
    }
}
