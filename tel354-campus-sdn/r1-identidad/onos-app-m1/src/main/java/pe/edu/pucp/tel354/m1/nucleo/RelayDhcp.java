package pe.edu.pucp.tel354.m1.nucleo;

import org.onlab.packet.Data;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.MacAddress;
import org.onlab.packet.UDP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Arrays;

/**
 * Fase 3 de R1: relay DHCP controlado (AVZ07, sección 4). Recibe el DHCP del host por la
 * regla 525 (solo existe con sesión AUTENTICADA o ACTIVA), agrega la opción 82 con el
 * pabellón (sub-opción 1 = N) y el rol (sub-opción 2 = k) y lo reenvía a Kea desde
 * 172.16.0.10:67. Con el DHCPACK pide al GestorSesiones la activación de la sesión.
 */
public class RelayDhcp {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final Parametros p;
    private final Plataforma plataforma;
    private final GestorSesiones gestor;

    private DatagramSocket socket;
    private InetSocketAddress kea;

    public RelayDhcp(Parametros p, Plataforma plataforma, GestorSesiones gestor) {
        this.p = p;
        this.plataforma = plataforma;
        this.gestor = gestor;
    }

    public void iniciar() throws Exception {
        kea = new InetSocketAddress(p.keaIp, 67);
        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getByName(p.relayIp), 67));
        Thread t = new Thread(this::recibir, "m1-dhcp-relay");
        t.setDaemon(true);
        t.start();
        log.info("M1: relay DHCP listo ({} -> Kea {}:67)", socket.getLocalSocketAddress(), p.keaIp);
    }

    public void detener() {
        if (socket != null) {
            socket.close();
        }
    }

    /** DHCP del cliente (UDP 68 -> 67): la carga UDP sin cabeceras. */
    public void desdeHost(String dpid, int puerto, String macOrigen, byte[] dhcp) {
        Dhcp.Mensaje m = Dhcp.leer(dhcp);
        if (m == null || m.op != 1) {
            return;
        }
        Sesion s = gestor.buscar(dpid, puerto, macOrigen);
        if (s == null) {
            log.debug("M1: DHCP de {} en {}/{} sin sesión: descartado", macOrigen, dpid, puerto);
            return;
        }
        if (!m.macCliente().equalsIgnoreCase(macOrigen)) {
            log.warn("M1: chaddr {} distinto de la MAC de la sesión {}: descartado", m.macCliente(), macOrigen);
            return;
        }
        try {
            byte[] salida = Dhcp.haciaServidor(dhcp, p.relayIp, s.pabellon, s.codigoRol());
            socket.send(new DatagramPacket(salida, salida.length, kea));
            log.debug("M1: DHCP tipo {} de {} relayado (N={}, k={})", m.tipo, macOrigen, s.pabellon, s.codigoRol());
        } catch (Exception e) {
            log.warn("M1: no se pudo relayar el DHCP de {}: {}", macOrigen, e.toString());
        }
    }

    private void recibir() {
        byte[] buf = new byte[2048];
        while (!socket.isClosed()) {
            DatagramPacket dg = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(dg);
                desdeServidor(Arrays.copyOf(dg.getData(), dg.getLength()));
            } catch (SocketException e) {
                break;
            } catch (Exception e) {
                log.warn("M1: respuesta DHCP descartada: {}", e.toString());
            }
        }
    }

    void desdeServidor(byte[] dhcp) {
        Dhcp.Mensaje m = Dhcp.leer(dhcp);
        if (m == null || m.op != 2) {
            return;
        }
        Sesion s = gestor.buscarPorMac(m.macCliente());
        if (s == null) {
            log.debug("M1: respuesta DHCP para {} sin sesión: descartada", m.macCliente());
            return;
        }
        if (m.tipo == Dhcp.ACK && !Dhcp.ceros(m.yiaddr)) {
            // Reglas antes de que el host use la IP. Si no se pudieron instalar, el ACK no se
            // entrega: el cliente repite el REQUEST y la activación se reintenta.
            if (!gestor.activar(s, Dhcp.ip(m.yiaddr))) {
                log.warn("M1: DHCPACK para {} retenido (activación fallida)", s);
                return;
            }
        } else if (m.tipo == Dhcp.NAK) {
            log.info("M1: DHCPNAK para {}", s);
        }
        byte[] payload = Dhcp.haciaCliente(dhcp);

        // Destino (RFC 2131, 4.1): ciaddr si el cliente ya tiene IP; si no, difusión o yiaddr
        Ip4Address destino;
        if (!Dhcp.ceros(m.ciaddr)) {
            destino = Ip4Address.valueOf(m.ciaddr);
        } else if (m.difusion() || Dhcp.ceros(m.yiaddr)) {
            destino = Ip4Address.valueOf("255.255.255.255");
        } else {
            destino = Ip4Address.valueOf(m.yiaddr);
        }
        UDP udp = new UDP();
        udp.setSourcePort(67);
        udp.setDestinationPort(68);
        udp.setPayload(new Data(payload));
        IPv4 ip = new IPv4();
        ip.setSourceAddress(Ip4Address.valueOf(p.relayIp).toInt());
        ip.setDestinationAddress(destino.toInt());
        ip.setTtl((byte) 64);
        ip.setProtocol(IPv4.PROTOCOL_UDP);
        ip.setPayload(udp);
        Ethernet eth = new Ethernet();
        eth.setSourceMACAddress(ProxyArp.macGateway(s.octeto));
        eth.setDestinationMACAddress(MacAddress.valueOf(m.chaddr));
        eth.setEtherType(EthTipos.IPV4);
        eth.setPayload(ip);
        plataforma.enviarTrama(s.dpid, s.puerto, eth.serialize());
    }
}
