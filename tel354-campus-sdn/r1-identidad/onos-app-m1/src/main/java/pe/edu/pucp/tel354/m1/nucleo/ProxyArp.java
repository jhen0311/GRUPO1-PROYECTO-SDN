package pe.edu.pucp.tel354.m1.nucleo;

import org.onlab.packet.ARP;
import org.onlab.packet.Ethernet;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.MacAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mínimo de M8 que necesita la demo: proxy ARP del gateway virtual (AVZ07, sección 5.5).
 * El host de la sesión pregunta por 10.b.0.1 (u otra IP de su /12) y M1 responde con la MAC
 * virtual 02:00:00:00:00:‹b›, así nunca hay ARP en difusión en el pabellón. El ARP llega
 * por la regla 800, que ya tiene su meter (arp_P).
 */
public class ProxyArp {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final Plataforma plataforma;
    private final GestorSesiones gestor;

    public ProxyArp(Plataforma plataforma, GestorSesiones gestor) {
        this.plataforma = plataforma;
        this.gestor = gestor;
    }

    public static MacAddress macGateway(int octeto) {
        return MacAddress.valueOf(String.format("02:00:00:00:00:%02x", octeto & 0xff));
    }

    public void desdeHost(String dpid, int puerto, Ethernet eth) {
        if (!(eth.getPayload() instanceof ARP)) {
            return;
        }
        ARP arp = (ARP) eth.getPayload();
        if (arp.getOpCode() != ARP.OP_REQUEST) {
            return;
        }
        Ip4Address emisor = Ip4Address.valueOf(arp.getSenderProtocolAddress());
        Ip4Address buscado = Ip4Address.valueOf(arp.getTargetProtocolAddress());
        // Sondeo de DHCP (emisor 0.0.0.0) o ARP gratuito: no se responde, la IP debe quedar libre
        if (emisor.toInt() == 0 || emisor.equals(buscado)) {
            return;
        }
        Sesion s = gestor.buscar(dpid, puerto, eth.getSourceMAC().toString());
        if (s == null || s.ip == null || !s.ip.equals(emisor.toString())) {
            log.debug("M1: ARP de {} ({}) sin sesión activa con esa IP: sin respuesta",
                      eth.getSourceMAC(), emisor);
            return;
        }
        Ethernet respuesta = ARP.buildArpReply(buscado, macGateway(s.octeto), eth);
        plataforma.enviarTrama(dpid, puerto, respuesta.serialize());
    }
}
