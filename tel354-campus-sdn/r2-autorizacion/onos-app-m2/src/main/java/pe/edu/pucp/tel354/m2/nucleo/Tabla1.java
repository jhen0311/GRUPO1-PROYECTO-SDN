package pe.edu.pucp.tel354.m2.nucleo;

import java.util.ArrayList;
import java.util.List;

/**
 * Tabla 1 (autorización) de un switch de acceso: la misma que cargaban onos_static.py y el puente
 * base_auto.py --solo-tabla1 (base_slice.py, reglas_acceso), ahora calculada por M2.
 *
 * <pre>
 *  100  ip src 10.‹b›.0.0/12, dst ‹recurso›/32, tcp|udp:‹puerto›  -> goto 2   (una por fila de v_permiso_efectivo)
 *   60  ip dst ‹DNS›/32 udp:53                                    -> goto 2
 *   58  vlan 999, ip dst 10.0.0.0/8                               -> drop     (Cuarentena aislada)
 *   58  vlan 999, ip dst 172.16.0.0/16                            -> drop
 *   57  vlan 999, ip                                              -> meter(cuarentena) goto 2   (1 Mbps a Internet)
 *   55  ip dst 10.0.0.0/8                                         -> meter(captura) CONTROLLER  (slow-path de M2)
 *   55  ip dst 172.16.0.0/16                                      -> meter(captura) CONTROLLER
 *   50  ip                                                        -> goto 2   (salida a Internet)
 *    0  *                                                         -> drop
 * </pre>
 *
 * Total: P + 8 reglas por switch de acceso (P = filas de v_permiso_efectivo), sin depender del
 * número de usuarios. Las 110 por curso las instala M1 con cada sesión.
 */
public final class Tabla1 {

    public static final int VLAN_CUARENTENA = 999;
    public static final int T_ENTREGA = 2;
    public static final String RED_CAMPUS = "10.0.0.0/8";
    public static final String RED_CONTROL = "172.16.0.0/16";

    private Tabla1() {
    }

    public static List<ReglaT1> reglas(Politica p, String dns) {
        List<ReglaT1> r = new ArrayList<>();
        for (Politica.Permiso x : p.permisos()) {
            r.add(ReglaT1.permiso(x.recursoId, x.codigoRol())
                          .desde("10." + x.octeto + ".0.0/12").hacia(x.ip + "/32").l4(x.proto, x.puerto)
                          .tabla(T_ENTREGA));
        }
        r.addAll(comunes(dns));
        return r;
    }

    /** Las 8 reglas que no dependen de la política. */
    public static List<ReglaT1> comunes(String dns) {
        List<ReglaT1> r = new ArrayList<>();
        r.add(ReglaT1.comun(60, 0).hacia(dns + "/32").l4(Politica.UDP, 53).tabla(T_ENTREGA));
        r.add(ReglaT1.comun(58, 1).conVlan(VLAN_CUARENTENA).hacia(RED_CAMPUS));
        r.add(ReglaT1.comun(58, 2).conVlan(VLAN_CUARENTENA).hacia(RED_CONTROL));
        r.add(ReglaT1.comun(57, 0).conVlan(VLAN_CUARENTENA).ip().conMeter(ReglaT1.Meter.CUARENTENA)
                      .tabla(T_ENTREGA));
        r.add(ReglaT1.comun(55, 1).hacia(RED_CAMPUS).conMeter(ReglaT1.Meter.CAPTURA).alControlador());
        r.add(ReglaT1.comun(55, 2).hacia(RED_CONTROL).conMeter(ReglaT1.Meter.CAPTURA).alControlador());
        r.add(ReglaT1.comun(50, 0).ip().tabla(T_ENTREGA));
        r.add(ReglaT1.comun(0, 0));
        return r;
    }
}
