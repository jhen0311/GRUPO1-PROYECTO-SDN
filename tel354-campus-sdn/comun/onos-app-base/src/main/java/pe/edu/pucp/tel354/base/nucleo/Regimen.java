package pe.edu.pucp.tel354.base.nucleo;

import pe.edu.pucp.tel354.api.Pipeline;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Reglas que M7 y M8 deben tener en cada switch (las mismas de base_auto.py sin la Tabla 1, que es
 * de M2). Funciones puras: dado el rol y los puertos del switch, devuelven el conjunto deseado.
 *
 * <pre>
 * Acceso (pabellón N)
 *   M7  T0 900  EAPOL -> controlador
 *       T0 800  in_port=p, ARP -> meter ctl_P(p), controlador      (una por puerto de host)
 *       T0 600  in_port=uplink -> Tabla 2
 *       T0  10  drop
 *   M8  T2 200  VLAN presente, IPv4 a 10.200.0.0/16 -> pop VLAN, uplink
 *       T2 180  VLAN presente, IPv4 -> pop VLAN, uplink
 * Core
 *   M8  210  VLAN 100+N, IPv4 -> puerto de N                       (por pabellón)
 *       210  VLAN 999, IPv4 a 10.(112+N).0.0/16 -> puerto de N
 *       205  IPv4 a 10.(112+N).0.0/16 -> push VLAN 999, puerto de N
 *       200  IPv4 a 10.(16k+N).0.0/16 -> push VLAN 100+N, puerto de N  (k = 1..6)
 *       200  IPv4 al prefijo de un destino fijo -> [MAC], su puerto     (servidores)
 *         0  drop
 * </pre>
 *
 * Con H puertos de host, P pabellones y D destinos fijos:
 * acceso = H + 5 reglas y H meters; core = 9P + D + 1 reglas.
 */
public final class Regimen {

    public static final int ETH_IPV4 = 0x0800;
    public static final int ETH_ARP = 0x0806;
    public static final int ETH_EAPOL = 0x888e;

    // Prioridades del core (M8)
    public static final int P_CORE_TRANSITO = 210;
    public static final int P_CORE_CUARENTENA = 205;
    public static final int P_CORE_RETORNO = 200;
    public static final int P_CORE_DEFECTO = 0;

    /** El plan 10.(16k+N).0.0/16 deja N entre 1 y 15 dentro del /12 de cada rol. */
    public static final int PABELLON_MAX = 15;

    /** Destino fijo del core: prefijo, MAC que se reescribe (o null) y puerto ya resuelto. */
    public static final class Destino {
        public final String prefijo;
        public final String mac;
        public final int puerto;

        public Destino(String prefijo, String mac, int puerto) {
            this.prefijo = prefijo;
            this.mac = mac;
            this.puerto = puerto;
        }
    }

    private Regimen() {
    }

    /**
     * Switch de acceso. Sin uplink conocido (aún no hay enlace con el core) solo se instalan las
     * reglas que no dependen de puertos: 900 y 10.
     */
    public static List<Regla> acceso(int uplink, Collection<Integer> hosts) {
        List<Regla> r = new ArrayList<>();
        r.add(new Regla(Regla.Modulo.M7, Pipeline.T_ADMISION, Pipeline.P_EAPOL, 0, 0)
                      .eth(ETH_EAPOL).salir(Regla.CONTROLADOR));
        r.add(new Regla(Regla.Modulo.M7, Pipeline.T_ADMISION, Pipeline.P_DEFECTO_T0, 0, 0));
        if (uplink <= 0) {
            return r;
        }
        for (int p : hosts) {
            r.add(new Regla(Regla.Modulo.M7, Pipeline.T_ADMISION, Pipeline.P_ARP, p, 0)
                          .enPuerto(p).eth(ETH_ARP).meter(p).salir(Regla.CONTROLADOR));
        }
        r.add(new Regla(Regla.Modulo.M7, Pipeline.T_ADMISION, Pipeline.P_UPLINK, 0, 0)
                      .enPuerto(uplink).tabla(Pipeline.T_ENTREGA));
        r.add(new Regla(Regla.Modulo.M8, Pipeline.T_ENTREGA, Pipeline.P_HOST_IP, 0, 0)
                      .conVlan(Regla.VLAN_PRESENTE).eth(ETH_IPV4).haciaIp("10.200.0.0/16").pop().salir(uplink));
        r.add(new Regla(Regla.Modulo.M8, Pipeline.T_ENTREGA, Pipeline.P_SALIDA_T2, 0, 0)
                      .conVlan(Regla.VLAN_PRESENTE).eth(ETH_IPV4).pop().salir(uplink));
        return r;
    }

    /**
     * Switch core. {@code pabellones}: número de pabellón → puerto del core hacia su switch de
     * acceso (solo los que ya tienen enlace). Los pabellones fuera de 1..15 se ignoran.
     */
    public static List<Regla> core(Map<Integer, Integer> pabellones, List<Destino> destinos) {
        List<Regla> r = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : pabellones.entrySet()) {
            int n = e.getKey();
            int p = e.getValue();
            if (n < 1 || n > PABELLON_MAX || p <= 0) {
                continue;
            }
            int vlan = 100 + n;                                        // Pipeline.vlanPabellon(n)
            String cuarentena = "10." + (16 * Pipeline.K_CUARENTENA + n) + ".0.0/16";
            r.add(new Regla(Regla.Modulo.M8, 0, P_CORE_TRANSITO, n, 0).conVlan(vlan).eth(ETH_IPV4).salir(p));
            r.add(new Regla(Regla.Modulo.M8, 0, P_CORE_TRANSITO, n, 1).conVlan(Pipeline.VLAN_CUARENTENA)
                          .eth(ETH_IPV4).haciaIp(cuarentena).salir(p));
            r.add(new Regla(Regla.Modulo.M8, 0, P_CORE_CUARENTENA, n, 0).eth(ETH_IPV4).haciaIp(cuarentena)
                          .push(Pipeline.VLAN_CUARENTENA).salir(p));
            for (int k = 1; k < Pipeline.K_CUARENTENA; k++) {
                r.add(new Regla(Regla.Modulo.M8, 0, P_CORE_RETORNO, n, k).eth(ETH_IPV4)
                              .haciaIp("10." + (16 * k + n) + ".0.0/16").push(vlan).salir(p));
            }
        }
        for (int i = 0; i < destinos.size(); i++) {
            Destino d = destinos.get(i);
            if (d.puerto <= 0) {
                continue;
            }
            Regla x = new Regla(Regla.Modulo.M8, 0, P_CORE_RETORNO, 0, i).eth(ETH_IPV4).haciaIp(d.prefijo);
            if (d.mac != null && !d.mac.isEmpty()) {
                x.mac(d.mac);
            }
            r.add(x.salir(d.puerto));
        }
        r.add(new Regla(Regla.Modulo.M8, 0, P_CORE_DEFECTO, 0, 0));
        return r;
    }
}
