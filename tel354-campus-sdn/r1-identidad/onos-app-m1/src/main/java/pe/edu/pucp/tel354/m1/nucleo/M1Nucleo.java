package pe.edu.pucp.tel354.m1.nucleo;

import org.onlab.packet.Ethernet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.onlab.packet.Ip4Address;

import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Núcleo de M1, independiente del controlador: recibe las tramas de los Packet-In y las reparte
 * entre el autenticador (EAPOL, regla 900), el relay DHCP (regla 525), el proxy ARP (regla 800)
 * y la detección de suplantación de IP (regla 505, cambio P2).
 */
public class M1Nucleo {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final BaseDatos bd;
    private final GestorSesiones gestor;
    private final Autenticador autenticador;
    private final RelayDhcp relay;
    private final ProxyArp proxyArp;
    private final KeaControl kea;
    /** Temporizadores del núcleo: 60 s sin DHCP y reintentos de EAP-Request/Identity. */
    private final ScheduledExecutorService temporizador;

    public M1Nucleo(Parametros p, Plataforma plataforma, Notificador aviso) {
        this.temporizador = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "m1-temporizador");
            t.setDaemon(true);
            return t;
        });
        this.bd = new BaseDatos(p);
        this.gestor = new GestorSesiones(p, bd, plataforma, aviso, temporizador);
        this.autenticador = new Autenticador(p, plataforma, gestor, aviso);
        this.relay = new RelayDhcp(p, plataforma, gestor);
        this.proxyArp = new ProxyArp(plataforma, gestor, aviso);
        this.kea = new KeaControl(p.keaCtrlUrl);
        gestor.alRehabilitar(autenticador::solicitarIdentidad);
    }

    public M1Nucleo(Parametros p, Plataforma plataforma) {
        this(p, plataforma, Notificador.NINGUNO);
    }

    public void iniciar() throws Exception {
        autenticador.iniciar();
        relay.iniciar();
    }

    /** @param cerrarSesiones true si la app se desactiva (no al apagar solo esta instancia) */
    public void detener(boolean cerrarSesiones) {
        try {
            gestor.detener(cerrarSesiones);
        } finally {
            temporizador.shutdownNow();
            autenticador.detener();
            relay.detener();
            bd.cerrar();
        }
    }

    public GestorSesiones gestor() {
        return gestor;
    }

    public KeaControl kea() {
        return kea;
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
                return otraIp(dpid, puerto, t);
            }
        } catch (Exception e) {
            log.warn("M1: trama de {}/{} descartada: {}", dpid, puerto, e.toString());
        }
        return false;
    }

    /**
     * IPv4 sin etiqueta que no es DHCP: solo puede venir de la regla 505 (P2). Con la sesión
     * ACTIVA y una IP de origen distinta de la concedida es una suplantación de IP; con la sesión
     * AUTENTICADA solo se descarta (AVZ07). Sin sesión en esta instancia no es de M1.
     */
    private boolean otraIp(String dpid, int puerto, byte[] t) {
        if (t.length < 34) {
            return false;
        }
        Sesion s = gestor.buscar(dpid, puerto, mac(t, 6));
        if (s == null) {
            return false;
        }
        Ip4Address origen = Ip4Address.valueOf(Arrays.copyOfRange(t, 26, 30));
        if (s.estado == Sesion.Estado.ACTIVA && s.ip != null && !s.ip.equals(origen.toString())
                && origen.toInt() != 0) {
            gestor.violacion(s, "IP", origen);
        }
        return true;
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
