package pe.edu.pucp.tel354.m1.nucleo;

import org.onlab.packet.EAP;
import org.onlab.packet.EAPOL;
import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onlab.packet.RADIUS;
import org.onlab.packet.RADIUSAttribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fase 1 de R1: autenticador 802.1X de paso (EAP pass-through, RFC 3579).
 *
 * M1 no interpreta el método EAP: copia cada EAP-Response del host en un Access-Request y cada
 * EAP-Request de FreeRADIUS en una trama EAPOL. El túnel EAP-TTLS queda de extremo a extremo
 * entre el supplicant y FreeRADIUS, así que el controlador nunca ve la contraseña.
 * Con el Access-Accept lee User-Name, Filter-Id, Session-Timeout y los VSA de PUCP y pide la
 * sesión al GestorSesiones.
 */
public class Autenticador {

    /** Estado de una conversación EAP en un puerto (una por MAC). */
    private static final class Conversacion {
        String dpid;
        int puerto;
        MacAddress mac;
        byte[] identidad;            // identidad externa (anonymous)
        byte[] state;                // atributo State del último Access-Challenge
        int radiusId = -1;
        byte[] reqAuth;              // Request Authenticator del último Access-Request
        byte[] ultimo;               // último Access-Request, para retransmitir
        long enviadoMs;
        int reintentos;
        long inicioMs = System.currentTimeMillis();
    }

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final Parametros p;
    private final Plataforma plataforma;
    private final GestorSesiones gestor;
    private final SecureRandom azar = new SecureRandom();

    private final Map<String, Conversacion> porClave = new ConcurrentHashMap<>();
    private final Conversacion[] porRadiusId = new Conversacion[256];
    private int siguienteId = azar.nextInt(256);

    private DatagramSocket socket;
    private Thread receptor;
    private ScheduledExecutorService temporizador;
    private InetSocketAddress servidor;
    private MacAddress macPropia;

    public Autenticador(Parametros p, Plataforma plataforma, GestorSesiones gestor) {
        this.p = p;
        this.plataforma = plataforma;
        this.gestor = gestor;
    }

    public void iniciar() throws SocketException {
        macPropia = MacAddress.valueOf(p.macAutenticador);
        servidor = new InetSocketAddress(p.radiusIp, p.radiusPuerto);
        try {
            socket = new DatagramSocket(new InetSocketAddress(InetAddress.getByName(p.nasIp), 0));
        } catch (java.net.UnknownHostException e) {
            throw new SocketException("NAS-IP inválida: " + p.nasIp);
        }
        receptor = new Thread(this::recibir, "m1-radius");
        receptor.setDaemon(true);
        receptor.start();
        temporizador = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "m1-radius-reintentos");
            t.setDaemon(true);
            return t;
        });
        temporizador.scheduleWithFixedDelay(this::revisarPendientes, 1, 1, TimeUnit.SECONDS);
        log.info("M1: autenticador 802.1X listo (NAS {} -> RADIUS {}:{})", socket.getLocalSocketAddress(),
                 p.radiusIp, p.radiusPuerto);
    }

    public void detener() {
        if (temporizador != null) {
            temporizador.shutdownNow();
        }
        if (socket != null) {
            socket.close();
        }
        porClave.clear();
    }

    // ------------------------------------------------------------------ host -> M1

    /** Trama EAPOL recibida por Packet-In (regla 900). */
    public void desdeHost(String dpid, int puerto, Ethernet eth) {
        if (!(eth.getPayload() instanceof EAPOL)) {
            return;
        }
        EAPOL eapol = (EAPOL) eth.getPayload();
        MacAddress mac = eth.getSourceMAC();
        String clave = Sesion.clave(dpid, puerto, mac.toString());
        switch (eapol.getEapolType()) {
            case EAPOL.EAPOL_START:
                Conversacion c = new Conversacion();
                c.dpid = dpid;
                c.puerto = puerto;
                c.mac = mac;
                porClave.put(clave, c);
                log.info("M1: EAPOL-Start de {} en {}/{}", mac, dpid, puerto);
                byte id = (byte) azar.nextInt(256);
                enviarEap(c, new EAP(EAP.REQUEST, id, EAP.ATTR_IDENTITY, null));
                break;
            case EAPOL.EAPOL_LOGOFF:
                log.info("M1: EAPOL-Logoff de {} en {}/{}", mac, dpid, puerto);
                porClave.remove(clave);
                gestor.cerrarPorLogoff(dpid, puerto, mac.toString());
                break;
            case EAPOL.EAPOL_PACKET:
                if (!(eapol.getPayload() instanceof EAP)) {
                    return;
                }
                EAP eap = (EAP) eapol.getPayload();
                if (eap.getCode() != EAP.RESPONSE) {
                    return;
                }
                Conversacion conv = porClave.get(clave);
                if (conv == null) {
                    // el supplicant respondió sin EAPOL-Start (p. ej., tras reiniciar M1)
                    conv = new Conversacion();
                    conv.dpid = dpid;
                    conv.puerto = puerto;
                    conv.mac = mac;
                    porClave.put(clave, conv);
                }
                if (eap.getDataType() == EAP.ATTR_IDENTITY) {
                    conv.identidad = eap.getData();
                    conv.state = null;
                    log.info("M1: identidad externa \"{}\" de {}", texto(conv.identidad), mac);
                }
                if (conv.identidad == null) {
                    log.debug("M1: EAP-Response sin identidad previa de {}; se ignora", mac);
                    return;
                }
                enviarAccessRequest(conv, eap);
                break;
            default:
                break;
        }
    }

    private synchronized void enviarAccessRequest(Conversacion c, EAP eap) {
        if (c.radiusId >= 0 && porRadiusId[c.radiusId] == c) {
            porRadiusId[c.radiusId] = null;           // la solicitud anterior de esta conversación
        }
        // Identificador libre (RFC 2865: único entre las solicitudes pendientes hacia el servidor).
        // Con 256 pendientes no se envía: el supplicant retransmite su EAP-Response en unos segundos.
        int id = -1;
        for (int k = 0; k < 256; k++) {
            int cand = (siguienteId + k) & 0xff;
            if (porRadiusId[cand] == null) {
                id = cand;
                break;
            }
        }
        if (id < 0) {
            log.warn("M1: 256 solicitudes RADIUS pendientes; se descarta la de {}", c.mac);
            return;
        }
        siguienteId = (id + 1) & 0xff;

        RADIUS r = new RADIUS(RADIUS.RADIUS_CODE_ACCESS_REQUEST, (byte) id);
        c.reqAuth = r.generateAuthCode().clone();
        r.setAttribute(Radius.ATTR_USER_NAME, c.identidad);
        r.setAttribute(Radius.ATTR_NAS_IP, ipBytes(p.nasIp));
        r.setAttribute(Radius.ATTR_NAS_PORT, Radius.entero(c.puerto));
        r.setAttribute(Radius.ATTR_NAS_PORT_TYPE, Radius.entero(15));            // Ethernet
        r.setAttribute(Radius.ATTR_NAS_PORT_ID, Radius.texto(c.dpid + "/" + c.puerto));
        r.setAttribute(Radius.ATTR_NAS_IDENTIFIER, Radius.texto("onos-m1"));
        r.setAttribute(Radius.ATTR_CALLED_STATION_ID, Radius.texto(c.dpid));
        r.setAttribute(Radius.ATTR_CALLING_STATION_ID,
                       Radius.texto(c.mac.toString().replace(':', '-').toUpperCase()));
        r.setAttribute(Radius.ATTR_FRAMED_MTU, Radius.entero(p.framedMtu));
        if (c.state != null) {
            r.setAttribute(Radius.ATTR_STATE, c.state);
        }
        r.encapsulateMessage(eap);
        r.addMessageAuthenticator(p.radiusSecreto);              // siempre al final

        c.radiusId = id;
        c.ultimo = r.serialize();
        c.enviadoMs = System.currentTimeMillis();
        c.reintentos = 0;
        porRadiusId[id] = c;
        enviarUdp(c.ultimo);
    }

    private void enviarUdp(byte[] datos) {
        try {
            socket.send(new DatagramPacket(datos, datos.length, servidor));
        } catch (Exception e) {
            log.warn("M1: no se pudo enviar a RADIUS {}: {}", servidor, e.toString());
        }
    }

    private synchronized void revisarPendientes() {
        long ahora = System.currentTimeMillis();
        for (Conversacion c : porClave.values()) {
            if (c.radiusId < 0 || c.ultimo == null) {
                continue;
            }
            if (ahora - c.enviadoMs < p.radiusEsperaSeg * 1000L) {
                continue;
            }
            if (c.reintentos >= p.radiusReintentos) {
                log.warn("M1: FreeRADIUS no respondió a {} tras {} reintentos", c.mac, c.reintentos);
                porRadiusId[c.radiusId] = null;
                c.radiusId = -1;
                c.ultimo = null;
                continue;
            }
            c.reintentos++;
            c.enviadoMs = ahora;
            enviarUdp(c.ultimo);                  // misma solicitud: mismo Id y autenticador
        }
        // conversaciones abandonadas (más de 2 minutos sin terminar)
        porClave.values().removeIf(c -> c.radiusId < 0 && ahora - c.inicioMs > 120_000
                && gestor.buscar(c.dpid, c.puerto, c.mac.toString()) == null);
    }

    // -------------------------------------------------------------- RADIUS -> M1

    private void recibir() {
        byte[] buf = new byte[4096];
        while (!socket.isClosed()) {
            DatagramPacket dg = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(dg);
                procesarRespuesta(Arrays.copyOf(dg.getData(), dg.getLength()));
            } catch (SocketException e) {
                break;                                     // socket cerrado al detener M1
            } catch (Exception e) {
                log.warn("M1: respuesta RADIUS descartada: {}", e.toString());
            }
        }
    }

    void procesarRespuesta(byte[] datos) throws Exception {
        if (datos.length < 20) {
            return;
        }
        int id = datos[1] & 0xff;
        Conversacion c;
        synchronized (this) {
            c = porRadiusId[id];
            if (c == null || c.reqAuth == null) {
                log.debug("M1: respuesta RADIUS con Id {} sin solicitud pendiente", id);
                return;
            }
            int len = ((datos[2] & 0xff) << 8) | (datos[3] & 0xff);
            if (len > datos.length || !Radius.respuestaValida(datos, len, c.reqAuth, p.radiusSecreto)) {
                log.warn("M1: Response Authenticator inválido (¿secreto distinto?): se descarta");
                return;
            }
            // RFC 3579: toda respuesta con EAP-Message debe traer un Message-Authenticator válido
            boolean conEap = tieneAtributo(datos, len, Radius.ATTR_EAP_MESSAGE);
            boolean conMa = tieneAtributo(datos, len, Radius.ATTR_MESSAGE_AUTH);
            if ((conEap || conMa) && !Radius.messageAuthenticatorValido(datos, len, c.reqAuth, p.radiusSecreto)) {
                log.warn("M1: Message-Authenticator ausente o inválido: se descarta");
                return;
            }
            porRadiusId[id] = null;
            c.radiusId = -1;
            c.ultimo = null;
        }

        RADIUS r = RADIUS.deserializer().deserialize(datos, 0, datos.length);
        EAP eap = r.getAttributeList(Radius.ATTR_EAP_MESSAGE).isEmpty() ? null : r.decapsulateMessage();

        switch (r.getCode()) {
            case RADIUS.RADIUS_CODE_ACCESS_CHALLENGE:
                RADIUSAttribute st = r.getAttribute(Radius.ATTR_STATE);
                synchronized (this) {
                    c.state = st != null ? st.getValue() : null;
                }
                if (eap != null) {
                    enviarEap(c, eap);
                }
                break;
            case RADIUS.RADIUS_CODE_ACCESS_ACCEPT:
                aceptar(c, r, eap);
                break;
            case RADIUS.RADIUS_CODE_ACCESS_REJECT:
                log.info("M1: Access-Reject para {} en {}/{}", c.mac, c.dpid, c.puerto);
                porClave.remove(Sesion.clave(c.dpid, c.puerto, c.mac.toString()));
                enviarEap(c, eap != null ? eap : new EAP(EAP.FAILURE, (byte) 0, (byte) 0, null));
                break;
            default:
                log.debug("M1: código RADIUS {} ignorado", r.getCode());
        }
    }

    private void aceptar(Conversacion c, RADIUS r, EAP eapExito) {
        String usuario = texto(valor(r, Radius.ATTR_USER_NAME));
        String rol = texto(valor(r, Radius.ATTR_FILTER_ID));
        int timeout = Radius.aEntero(valor(r, Radius.ATTR_SESSION_TIMEOUT));
        int maxSes = -1;
        for (RADIUSAttribute a : r.getAttributeList(Radius.ATTR_VSA)) {
            int v = Radius.vsaPucp(a.getValue(), Radius.VSA_MAX_SESSIONS);
            if (v > 0) {
                maxSes = v;
            }
        }
        porClave.remove(Sesion.clave(c.dpid, c.puerto, c.mac.toString()));
        log.info("M1: Access-Accept {} rol {} (Session-Timeout {} s) para {} en {}/{}",
                 usuario, rol, timeout, c.mac, c.dpid, c.puerto);

        Sesion s = (usuario.isEmpty() || rol.isEmpty()) ? null
                : gestor.abrir(c.dpid, c.puerto, c.mac.toString(), usuario, rol, timeout, maxSes);
        if (s == null) {
            byte eid = eapExito != null ? eapExito.getIdentifier() : 0;
            enviarEap(c, new EAP(EAP.FAILURE, eid, (byte) 0, null));
            return;
        }
        enviarEap(c, eapExito != null ? eapExito : new EAP(EAP.SUCCESS, (byte) 0, (byte) 0, null));
    }

    // ---------------------------------------------------------------- M1 -> host

    private void enviarEap(Conversacion c, EAP eap) {
        EAPOL eapol = new EAPOL();
        eapol.setVersion((byte) 2);
        eapol.setEapolType(EAPOL.EAPOL_PACKET);
        eapol.setPacketLength(eap.getLength());
        eapol.setPayload(eap);
        Ethernet eth = new Ethernet();
        eth.setDestinationMACAddress(c.mac);
        eth.setSourceMACAddress(macPropia);
        eth.setEtherType(EthTipos.EAPOL);
        eth.setPayload(eapol);
        plataforma.enviarTrama(c.dpid, c.puerto, eth.serialize());
    }

    // ------------------------------------------------------------------ utilidades

    private static byte[] valor(RADIUS r, byte tipo) {
        RADIUSAttribute a = r.getAttribute(tipo);
        return a != null ? a.getValue() : null;
    }

    private static String texto(byte[] b) {
        return b == null ? "" : new String(b, StandardCharsets.UTF_8).trim();
    }

    private static boolean tieneAtributo(byte[] pkt, int len, byte tipo) {
        int i = 20;
        while (i + 2 <= len) {
            int l = pkt[i + 1] & 0xff;
            if (l < 2) {
                return false;
            }
            if (pkt[i] == tipo) {
                return true;
            }
            i += l;
        }
        return false;
    }

    private static byte[] ipBytes(String ip) {
        try {
            return InetAddress.getByName(ip).getAddress();
        } catch (Exception e) {
            return new byte[4];
        }
    }
}
