package pe.edu.pucp.tel354.m1.nucleo;

import org.onlab.packet.Ip4Address;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Fases 2, 3 y 4 de la sesión (AVZ07, secciones 6.3 a 6.5): admisión tras el Access-Accept,
 * activación con el DHCPACK y cierre. Es el dueño de las tablas sesion y dispositivo.
 *
 * En el clúster, cada instancia guarda en memoria solo las sesiones de los switches de los que
 * es master ({@link #adoptar(String)} y {@link #soltar(String)}); PostgreSQL y las propias reglas
 * en el switch permiten que otra instancia las reconstruya si la maestría cambia.
 */
public class GestorSesiones {

    /** Motivos de cierre (columna sesion.motivo_cierre). */
    public static final String DESCONEXION = "DESCONEXION";
    public static final String EXPIRACION = "EXPIRACION";
    public static final String REVOCACION = "REVOCACION";
    public static final String MOVILIDAD = "MOVILIDAD";
    public static final String LIMITE_SESIONES = "LIMITE_SESIONES";
    public static final String CUARENTENA = "CUARENTENA";
    public static final String TABLA_LLENA = "TABLA_LLENA";
    public static final String REINICIO = "REINICIO";

    /** Índice de la regla 520 en la cookie (comprueba que una sesión ACTIVA sigue completa). */
    static final int IDX_525 = 0;
    static final int IDX_520 = 2;

    private static final int VLAN_CUARENTENA = 999;

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final Parametros p;
    private final BaseDatos bd;
    private final Plataforma plataforma;
    private final Notificador aviso;
    private final ScheduledExecutorService temporizador;

    private final Map<String, Sesion> porClave = new ConcurrentHashMap<>();   // dpid/puerto/mac
    private final Map<Long, Sesion> porId = new ConcurrentHashMap<>();

    /** EAP-Request/Identity al rehabilitar un puerto rebotado (lo pone M1Nucleo). */
    private BiConsumer<String, Integer> alRehabilitar = (d, pt) -> { };
    /** Cierre de una sesión que vive en otra instancia del clúster (lo pone M1Manager). */
    private BiConsumer<Long, String> cierreRemoto;

    public GestorSesiones(Parametros p, BaseDatos bd, Plataforma plataforma, Notificador aviso,
                          ScheduledExecutorService temporizador) {
        this.p = p;
        this.bd = bd;
        this.plataforma = plataforma;
        this.aviso = aviso;
        this.temporizador = temporizador;
        this.cierreRemoto = (id, motivo) -> {
            try {
                bd.cerrarSesion(id, motivo);
            } catch (Exception e) {
                log.warn("M1: no se pudo cerrar la sesión {} en la BD: {}", id, e.toString());
            }
        };
        plataforma.alFallarInstalacion(this::falloInstalacion);
    }

    public void alRehabilitar(BiConsumer<String, Integer> f) {
        alRehabilitar = f;
    }

    public void cierreRemoto(BiConsumer<Long, String> f) {
        cierreRemoto = f;
    }

    // ------------------------------------------------------------- maestría (clúster)

    /**
     * Esta instancia pasó a ser master del switch: reconstruye sus sesiones abiertas desde la BD
     * y las reglas que siguen en el switch. Una sesión sin sus reglas se cierra con REINICIO y su
     * puerto se rebota; las reglas de sesiones ya cerradas se retiran.
     *
     * @return sesiones recuperadas
     */
    public synchronized int adoptar(String dpid) throws Exception {
        List<BaseDatos.FilaSesion> filas = bd.sesionesAbiertasEn(dpid);
        Map<Long, List<ReglaRecuperada>> reglas = plataforma.recuperar(dpid);
        Set<Long> abiertas = new HashSet<>();
        int n = 0;
        for (BaseDatos.FilaSesion f : filas) {
            abiertas.add(f.id);
            if (porId.containsKey(f.id)) {
                continue;
            }
            List<ReglaRecuperada> rr = reglas.getOrDefault(f.id, Collections.<ReglaRecuperada>emptyList());
            boolean activa = "ACTIVA".equals(f.estado) && f.ip != null;
            int clave = activa ? IDX_520 : IDX_525;
            boolean completa = rr.stream().anyMatch(r -> r.indice == clave);
            Sesion s = f.aSesion();
            if (!completa) {
                retirarRecuperadas(rr);
                bd.cerrarSesion(f.id, REINICIO);
                s.estado = Sesion.Estado.CERRADA;
                aviso.sesionCerrada(s, REINICIO);
                log.info("M1: {} sin reglas en el switch: CERRADA (REINICIO)", s);
                rebotar(s);
                continue;
            }
            int restante = Integer.MAX_VALUE;
            for (ReglaRecuperada r : rr) {
                restante = Math.min(restante, r.restanteSeg);
                (r.deAutenticacion() ? s.reglasAutenticacion : s.reglasActivacion).add(r.regla);
            }
            s.timeoutSeg = Math.max(1, restante);
            s.inicioMs = System.currentTimeMillis();
            s.estado = activa ? Sesion.Estado.ACTIVA : Sesion.Estado.AUTENTICADA;
            s.discoverVisto = activa;
            porClave.put(s.clave(), s);
            porId.put(s.id, s);
            if (!activa) {
                programarSinDhcp(s);
            }
            n++;
            log.info("M1: {} recuperada ({} s restantes)", s, s.timeoutSeg);
        }
        for (Map.Entry<Long, List<ReglaRecuperada>> e : reglas.entrySet()) {
            if (!abiertas.contains(e.getKey())) {
                retirarRecuperadas(e.getValue());
                log.info("M1: reglas de la sesión cerrada {} retiradas de {}", e.getKey(), dpid);
            }
        }
        return n;
    }

    /** Esta instancia dejó de ser master: olvida las sesiones del switch sin tocar sus reglas. */
    public synchronized int soltar(String dpid) {
        int n = 0;
        for (Sesion s : new ArrayList<>(porId.values())) {
            if (s.dpid.equals(dpid)) {
                porClave.remove(s.clave(), s);
                porId.remove(s.id, s);
                n++;
            }
        }
        return n;
    }

    private void retirarRecuperadas(List<ReglaRecuperada> rr) {
        List<Object> l = new ArrayList<>();
        for (ReglaRecuperada r : rr) {
            l.add(r.regla);
        }
        if (!l.isEmpty()) {
            plataforma.retirar(l);
        }
    }

    /**
     * Al detener M1. Si la app se desactiva ({@code cerrarSesiones}), se cierran las sesiones
     * locales con REINICIO y se rebotan sus puertos; si solo se apaga esta instancia, las sesiones
     * se conservan para que el nuevo master las adopte.
     */
    public synchronized void detener(boolean cerrarSesiones) {
        if (!cerrarSesiones) {
            porClave.clear();
            porId.clear();
            return;
        }
        List<String> puertos = new ArrayList<>();
        for (Sesion s : new ArrayList<>(porId.values())) {
            cerrarSinRebote(s, REINICIO);
            puertos.add(s.dpid + "/" + s.puerto);
        }
        if (!puertos.isEmpty()) {
            plataforma.rebotarAhora(puertos, p.rebotePuertoSeg);
        }
    }

    // ------------------------------------------------------------------ consultas

    public Sesion buscar(String dpid, int puerto, String mac) {
        return porClave.get(Sesion.clave(dpid, puerto, mac));
    }

    public Sesion buscarPorMac(String mac) {
        String m = mac.toLowerCase();
        for (Sesion s : porClave.values()) {
            if (s.mac.equals(m)) {
                return s;
            }
        }
        return null;
    }

    public Sesion buscarPorId(long id) {
        return porId.get(id);
    }

    public Sesion buscarPorIp(String ip) {
        for (Sesion s : porId.values()) {
            if (ip.equals(s.ip) && s.estado == Sesion.Estado.ACTIVA) {
                return s;
            }
        }
        return null;
    }

    public List<Sesion> sesiones() {
        return Collections.unmodifiableList(new ArrayList<>(porId.values()));
    }

    public BaseDatos bd() {
        return bd;
    }

    // ------------------------------------------------------------------ fases

    /**
     * Fase 2: con el Access-Accept, registra el equipo y la sesión e instala 525 y 505.
     *
     * @return la sesión, o null si no se puede admitir (el autenticador envía EAP-Failure)
     */
    public synchronized Sesion abrir(String dpid, int puerto, String mac, String usuario, String rol,
                                     int timeoutSeg, int maxSesionesRadius) {
        mac = mac.toLowerCase();
        try {
            Integer pab = bd.pabellon(dpid);
            if (pab == null) {
                log.warn("M1: {} no es un switch de acceso registrado; se rechaza {}", dpid, usuario);
                return null;
            }
            Integer uid = bd.usuarioId(usuario);
            BaseDatos.Rol r = bd.rol(rol);
            if (uid == null || r == null) {
                log.warn("M1: usuario {} o rol {} sin fila activa en la BD; se rechaza", usuario, rol);
                return null;
            }

            // Un equipo, una sesión. Si la MAC ya tenía sesión en este mismo puerto, sus reglas se
            // retiran en una etapa previa a las nuevas (son iguales salvo la cookie); si estaba en
            // otro puerto, el equipo se movió.
            List<Object> retirarAntes = new ArrayList<>();
            String ipPrevia = null;
            String rolPrevio = null;
            Sesion previa = buscarPorMac(mac);
            if (previa != null) {
                boolean mismoPuerto = previa.dpid.equals(dpid) && previa.puerto == puerto;
                if (mismoPuerto) {
                    ipPrevia = previa.ip;
                    rolPrevio = previa.rol;
                    retirarAntes.addAll(desvincular(previa));
                    bd.cerrarSesion(previa.id, DESCONEXION);
                    aviso.sesionCerrada(previa, DESCONEXION);
                    log.info("M1: CERRADA {} (reautenticación)", previa);
                } else {
                    cerrar(previa, MOVILIDAD);
                }
            }
            // El equipo pudo tener sesión en un switch de otra instancia del clúster
            for (BaseDatos.FilaSesion f : bd.sesionesAbiertasDeMac(mac)) {
                if (!porId.containsKey(f.id)) {
                    cierreRemoto.accept(f.id, MOVILIDAD);
                }
            }

            Sesion s = new Sesion();
            s.dpid = dpid;
            s.puerto = puerto;
            s.mac = mac;
            s.usuario = usuario;
            s.usuarioId = uid;
            s.rol = rol;
            s.rolId = r.id;
            s.octeto = r.octeto;
            s.pabellon = pab;
            s.vlan = "CUARENTENA".equals(rol) ? VLAN_CUARENTENA : 100 + pab;
            s.timeoutSeg = timeoutSeg > 0 ? timeoutSeg : 3600;
            s.dispositivoId = bd.upsertDispositivo(mac, dpid, puerto);
            bd.cerrarSesionesDispositivo(s.dispositivoId, MOVILIDAD);

            // Límite de sesiones simultáneas del rol (PUCP-Max-Sessions o rol.max_sesiones)
            int max = maxSesionesRadius > 0 ? maxSesionesRadius : r.maxSesiones;
            List<Long> abiertas = bd.sesionesAbiertas(uid);
            while (abiertas.size() >= max) {
                long vieja = abiertas.remove(0);
                Sesion sv = porId.get(vieja);
                if (sv != null) {
                    // Sin rebote (AVZ07, Tabla 20): rebotar haría que el equipo desplazado se
                    // reautentique solo y desplace a otra sesión, en un ciclo sin fin.
                    cerrar(sv, LIMITE_SESIONES);
                } else {
                    cierreRemoto.accept(vieja, LIMITE_SESIONES);
                }
                log.info("M1: {} superó {} sesiones; se cerró la sesión {}", usuario, max, vieja);
            }

            s.id = bd.abrirSesion(s);
            s.reglasAutenticacion.addAll(plataforma.instalarAutenticacion(s, retirarAntes));
            porClave.put(s.clave(), s);
            porId.put(s.id, s);
            log.info("M1: AUTENTICADA {}", s);

            // Reautenticación en el mismo puerto: el equipo conserva su lease, así que la sesión se
            // activa con la misma IP sin esperar a la renovación DHCP.
            if (ipPrevia != null && rol.equals(rolPrevio)) {
                activar(s, ipPrevia);
            } else {
                programarSinDhcp(s);
            }
            return s;
        } catch (Exception e) {
            log.error("M1: error al abrir la sesión de {} en {}/{}: {}", usuario, dpid, puerto, e.toString());
            return null;
        }
    }

    /** Cierre por «60 s sin DHCP» (AVZ07, Tabla 20): el host no completó la fase 3. */
    private void programarSinDhcp(Sesion s) {
        final long id = s.id;
        temporizador.schedule(() -> sinDhcp(id), p.sinDhcpSeg, TimeUnit.SECONDS);
    }

    private synchronized void sinDhcp(long id) {
        Sesion s = porId.get(id);
        if (s != null && s.estado == Sesion.Estado.AUTENTICADA) {
            log.info("M1: {} sin DHCP en {} s", s, p.sinDhcpSeg);
            cerrar(s, DESCONEXION);
        }
    }

    /**
     * Fase 3: con el DHCPACK, IP de la sesión, reglas 520, 110 y 200/190 y estado ACTIVA.
     *
     * @return false si no se pudo activar: el relay no entrega el ACK y el cliente reintenta
     */
    public synchronized boolean activar(Sesion s, String ip) {
        if (s.estado == Sesion.Estado.CERRADA) {
            return false;
        }
        if (ip.equals(s.ip) && s.estado == Sesion.Estado.ACTIVA) {
            return true;                                      // renovación con la misma IP
        }
        try {
            // Cuarentena e Invitado nunca reciben reglas 110 (saltarían el aislamiento de la regla 58)
            List<Plataforma.AccesoCurso> cursos = ("CUARENTENA".equals(s.rol) || "INVITADO".equals(s.rol))
                    ? Collections.<Plataforma.AccesoCurso>emptyList() : bd.accesosCurso(s.usuario);
            List<Object> viejas = new ArrayList<>(s.reglasActivacion);   // cambio de IP
            s.reglasActivacion.clear();
            String ipAnterior = s.ip;
            s.ip = ip;                                        // las reglas se construyen con la IP nueva
            try {
                s.reglasActivacion.addAll(plataforma.instalarActivacion(s, cursos, viejas));
            } catch (RuntimeException e) {
                s.ip = ipAnterior;
                throw e;
            }
            s.estado = Sesion.Estado.ACTIVA;
            try {
                bd.activarSesion(s.id, ip);
            } catch (Exception e) {
                log.warn("M1: {} activa en la red, pero no se pudo registrar la IP en la BD: {}", s, e.toString());
            }
            log.info("M1: ACTIVA {} con {} reglas 110 (SessionReady)", s, cursos.size());
            aviso.sesionLista(s);
            return true;
        } catch (Exception e) {
            log.error("M1: error al activar {}: {}", s, e.toString());
            return false;
        }
    }

    /** Quita la sesión de los índices y devuelve sus reglas (sin retirarlas todavía). */
    private List<Object> desvincular(Sesion s) {
        s.estado = Sesion.Estado.CERRADA;
        porClave.remove(s.clave(), s);
        porId.remove(s.id, s);
        List<Object> todas = new ArrayList<>(s.reglasActivacion);
        todas.addAll(s.reglasAutenticacion);
        s.reglasActivacion.clear();
        s.reglasAutenticacion.clear();
        return todas;
    }

    /** ¿El cierre con este motivo rebota el puerto? (tel354-api, Motivo.rebota) */
    boolean rebota(String motivo) {
        switch (motivo) {
            case EXPIRACION:
                return p.rebotarPuertoAlExpirar;
            case REVOCACION:
            case CUARENTENA:
            case REINICIO:
                return true;
            default:
                return false;
        }
    }

    /** Fase 4: retira las reglas de la sesión, la marca CERRADA y, según el motivo, rebota el puerto. */
    public synchronized void cerrar(Sesion s, String motivo) {
        if (cerrarSinRebote(s, motivo) && rebota(motivo)) {
            rebotar(s);
        }
    }

    private boolean cerrarSinRebote(Sesion s, String motivo) {
        if (s.estado == Sesion.Estado.CERRADA) {
            return false;
        }
        List<Object> todas = desvincular(s);
        try {
            plataforma.retirar(todas);
        } catch (Exception e) {
            log.warn("M1: error al retirar las reglas de {}: {}", s, e.toString());
        }
        try {
            bd.cerrarSesion(s.id, motivo);
        } catch (Exception e) {
            log.warn("M1: error al cerrar {} en la BD: {}", s, e.toString());
        }
        log.info("M1: CERRADA {} ({})", s, motivo);
        aviso.sesionCerrada(s, motivo);
        return true;
    }

    private void rebotar(Sesion s) {
        final String dpid = s.dpid;
        final int puerto = s.puerto;
        plataforma.rebotarPuerto(dpid, puerto, p.rebotePuertoSeg, () -> alRehabilitar.accept(dpid, puerto));
    }

    /** EAPOL-Logoff del host. */
    public void cerrarPorLogoff(String dpid, int puerto, String mac) {
        Sesion s = buscar(dpid, puerto, mac);
        if (s != null) {
            cerrar(s, DESCONEXION);
        }
    }

    /** Enlace caído (PORT_UPDATED con el puerto deshabilitado). */
    public synchronized int puertoCaido(String dpid, int puerto) {
        int n = 0;
        for (Sesion s : new ArrayList<>(porId.values())) {
            if (s.dpid.equals(dpid) && s.puerto == puerto) {
                cerrar(s, DESCONEXION);
                n++;
            }
        }
        return n;
    }

    /** El switch rechazó las reglas de la sesión (FlowRuleOperationsContext.onError). */
    public synchronized void falloInstalacion(long id) {
        Sesion s = porId.get(id);
        if (s != null) {
            log.warn("M1: el switch rechazó las reglas de {}", s);
            cerrar(s, TABLA_LLENA);
        }
    }

    /**
     * Se retiró una regla con la cookie de la sesión. Solo es el fin de la sesión si la regla
     * sigue siendo de ella: las que retira el propio M1 (cambio de IP, cierre) ya no lo son.
     */
    public synchronized void alExpirar(long sesionId, Predicate<Object> esLaRegla) {
        Sesion s = porId.get(sesionId);
        if (s == null) {
            return;
        }
        boolean suya = s.reglasAutenticacion.stream().anyMatch(esLaRegla)
                || s.reglasActivacion.stream().anyMatch(esLaRegla);
        if (suya) {
            cerrar(s, EXPIRACION);
        }
    }

    // ------------------------------------------------------------- operaciones de R3 (P6)

    /** Revocación de una sesión local. @return false si no está en esta instancia. */
    public synchronized boolean revocar(long sesionId, String motivo) {
        Sesion s = porId.get(sesionId);
        if (s == null) {
            return false;
        }
        cerrar(s, motivo);
        return true;
    }

    /**
     * Paso forzado a cuarentena de las sesiones locales del usuario (LLSD R3, sección 5.2):
     * cierre con CUARENTENA (retiro de reglas y rebote), lease4-del en Kea mientras el puerto está
     * abajo y EAP-Request/Identity al rehabilitarlo.
     */
    public synchronized int aplicarCuarentena(int usuarioId, KeaControl kea) {
        int n = 0;
        for (Sesion s : new ArrayList<>(porId.values())) {
            if (s.usuarioId != usuarioId) {
                continue;
            }
            String ip = s.ip;
            cerrar(s, CUARENTENA);
            if (ip != null) {
                kea.borrarLease(ip);
            }
            n++;
        }
        return n;
    }

    /**
     * BindingViolation: a lo sumo una por segundo y por IP en cada sesión (la regla 505 y la 800
     * ya están limitadas por el meter ctl_P a 10 pps).
     */
    public void violacion(Sesion s, String tipo, Ip4Address ipUsada) {
        long ahora = System.currentTimeMillis();
        String ip = ipUsada.toString();
        synchronized (s) {
            if (ip.equals(s.ultimaIpViolacion) && ahora - s.ultimaViolacionMs < 1000) {
                return;
            }
            s.ultimaIpViolacion = ip;
            s.ultimaViolacionMs = ahora;
        }
        boolean inequivoca = esInequivoca(ipUsada);
        log.warn("M1: BindingViolation ({}) {} usa {} (concedida {}){}", tipo, s, ip, s.ip,
                 inequivoca ? " [inequívoca]" : "");
        aviso.violacion(s, tipo, ip, inequivoca);
    }

    /** Gateway virtual de un rol, servidor (10.200.0.0/16) o plano de control (172.16.0.0/16). */
    static boolean esInequivoca(Ip4Address ip) {
        byte[] b = ip.toOctets();
        int o1 = b[0] & 0xff;
        int o2 = b[1] & 0xff;
        boolean gateway = o1 == 10 && o2 % 16 == 0 && o2 >= 16 && o2 <= 112 && b[2] == 0 && b[3] == 1;
        boolean servidor = o1 == 10 && o2 == 200;
        boolean control = o1 == 172 && o2 == 16;
        return gateway || servidor || control;
    }
}
