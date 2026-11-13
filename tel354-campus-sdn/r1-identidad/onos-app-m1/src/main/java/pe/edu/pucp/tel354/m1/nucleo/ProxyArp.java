package pe.edu.pucp.tel354.m1.nucleo;

import org.onlab.packet.ARP;
import org.onlab.packet.Ethernet;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.MacAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mínimo de M8 dentro de M1: proxy ARP del gateway virtual, ArpProbe y BindingViolation (ARP)
 * (LLSD R3, sección 5.1; cambios P1 y P3 de M1 v1.1). El ARP llega por la regla 800, que ya
 * tiene su meter ctl_P (10 pps por puerto).
 *
 * Solo se responde por el gateway 10.‹b›.0.1 del rol de la sesión: un `nmap -sn` dentro del /12
 * no descubre ningún host. La comprobación del emisor va antes que la del ARP gratuito, porque
 * arpspoof envía respuestas o ARP gratuitos que anuncian la IP del gateway con la MAC del atacante.
 */
public class ProxyArp {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final Plataforma plataforma;
    private final GestorSesiones gestor;
    private final Notificador aviso;

    public ProxyArp(Plataforma plataforma, GestorSesiones gestor, Notificador aviso) {
        this.plataforma = plataforma;
        this.gestor = gestor;
        this.aviso = aviso;
    }

    public static MacAddress macGateway(int octeto) {
        return MacAddress.valueOf(String.format("02:00:00:00:00:%02x", octeto & 0xff));
    }

    public static Ip4Address gateway(int octeto) {
        return Ip4Address.valueOf("10." + octeto + ".0.1");
    }

    public void desdeHost(String dpid, int puerto, Ethernet eth) {
        if (!(eth.getPayload() instanceof ARP)) {
            return;
        }
        ARP arp = (ARP) eth.getPayload();
        Sesion s = gestor.buscar(dpid, puerto, eth.getSourceMAC().toString());
        if (s == null || s.estado != Sesion.Estado.ACTIVA || s.ip == null) {
            return;                                         // aún sin IP, o equipo sin sesión
        }
        Ip4Address emisor = Ip4Address.valueOf(arp.getSenderProtocolAddress());
        Ip4Address buscado = Ip4Address.valueOf(arp.getTargetProtocolAddress());
        if (emisor.toInt() == 0) {
            return;                                         // sondeo de DHCP (RFC 5227)
        }
        if (!emisor.toString().equals(s.ip)) {
            gestor.violacion(s, "ARP", emisor);              // incluye respuestas y ARP gratuitos falsos
            return;
        }
        if (arp.getOpCode() != ARP.OP_REQUEST || emisor.equals(buscado)) {
            return;                                         // respuesta o ARP gratuito con su propia IP
        }
        if (buscado.equals(gateway(s.octeto))) {
            Ethernet respuesta = ARP.buildArpReply(buscado, macGateway(s.octeto), eth);
            plataforma.enviarTrama(dpid, puerto, respuesta.serialize());
            return;
        }
        log.debug("M1: ArpProbe {} busca {}", s, buscado);
        aviso.sondeoArp(s, buscado.toString());            // no se responde
    }
}
