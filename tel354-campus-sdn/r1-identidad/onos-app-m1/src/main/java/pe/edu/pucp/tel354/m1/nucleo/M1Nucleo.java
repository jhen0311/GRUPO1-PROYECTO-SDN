package pe.edu.pucp.tel354.m1.nucleo;

import org.onlab.packet.Ethernet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Núcleo de M1, independiente del controlador: recibe las tramas de los Packet-In y las reparte
 * entre el autenticador (EAPOL, regla 900), el relay DHCP (regla 525) y el proxy ARP (regla 800).
 */
public class M1Nucleo {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final BaseDatos bd;
    private final GestorSesiones gestor;
    private final Autenticador autenticador;
    private final RelayDhcp relay;
    private final ProxyArp proxyArp;

    public M1Nucleo(Parametros p, Plataforma plataforma) {
        this.bd = new BaseDatos(p);
        this.gestor = new GestorSesiones(p, bd, plataforma);
        this.autenticador = new Autenticador(p, plataforma, gestor);
        this.relay = new RelayDhcp(p, plataforma, gestor);
        this.proxyArp = new ProxyArp(plataforma, gestor);
    }

    public void iniciar() throws Exception {
        gestor.iniciar();
        autenticador.iniciar();
        relay.iniciar();
    }

    public void detener() {
        try {
            gestor.detener();
        } finally {
            autenticador.detener();
            relay.detener();
            bd.cerrar();
        }
    }

    public GestorSesiones gestor() {
        return gestor;
    }

    /**
     * Trama completa recibida en un puerto de un switch.
     *
     * @return true si la trama era para M1 (EAPOL, DHCP de cliente o solicitud ARP)
     */
    public boolean procesarTrama(String dpid, int puerto, byte[] t) {
        if (t == null || t.length < 14) {
            return false;
        }
        int tipo = ((t[12] & 0xff) << 8) | (t[13] & 0xff);
        if (tipo == (EthTipos.VLAN & 0xffff)) {
            return false;                                   // los hosts no envían tramas etiquetadas
        }
        try {
            if (tipo == (EthTipos.EAPOL & 0xffff)) {
                autenticador.desdeHost(dpid, puerto, Ethernet.deserializer().deserialize(t, 0, t.length));
                return true;
            }
            if (tipo == EthTipos.ARP) {
                proxyArp.desdeHost(dpid, puerto, Ethernet.deserializer().deserialize(t, 0, t.length));
                return true;
            }
            if (tipo == EthTipos.IPV4) {
                byte[] dhcp = cargaDhcpCliente(t);
                if (dhcp != null) {
                    relay.desdeHost(dpid, puerto, mac(t, 6), dhcp);
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("M1: trama de {}/{} descartada: {}", dpid, puerto, e.toString());
        }
        return false;
    }

    /** Carga UDP si la trama es IPv4/UDP con origen 68 y destino 67; si no, null. */
    static byte[] cargaDhcpCliente(byte[] t) {
        int ip = 14;
        if (t.length < ip + 20 || (t[ip] >> 4 & 0xf) != 4 || (t[ip + 9] & 0xff) != 17) {
            return null;
        }
        int ihl = (t[ip] & 0xf) * 4;
        int totalIp = ((t[ip + 2] & 0xff) << 8) | (t[ip + 3] & 0xff);
        int udp = ip + ihl;
        if (t.length < udp + 8) {
            return null;
        }
        int origen = ((t[udp] & 0xff) << 8) | (t[udp + 1] & 0xff);
        int destino = ((t[udp + 2] & 0xff) << 8) | (t[udp + 3] & 0xff);
        int largo = ((t[udp + 4] & 0xff) << 8) | (t[udp + 5] & 0xff);
        if (origen != 68 || destino != 67 || largo < 8) {
            return null;
        }
        int fin = Math.min(Math.min(t.length, ip + totalIp), udp + largo);
        return fin > udp + 8 ? Arrays.copyOfRange(t, udp + 8, fin) : null;
    }

    static String mac(byte[] t, int i) {
        return String.format("%02x:%02x:%02x:%02x:%02x:%02x", t[i] & 0xff, t[i + 1] & 0xff, t[i + 2] & 0xff,
                             t[i + 3] & 0xff, t[i + 4] & 0xff, t[i + 5] & 0xff);
    }
}
