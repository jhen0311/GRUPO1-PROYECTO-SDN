package pe.edu.pucp.tel354.m1.nucleo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Fases 2, 3 y 4 de la sesión (AVZ07, secciones 6.3 a 6.5): admisión tras el Access-Accept,
 * activación con el DHCPACK y cierre. Es el dueño de las tablas sesion y dispositivo.
 */
public class GestorSesiones {

    /** Motivos de cierre permitidos por la tabla sesion. */
    public static final String DESCONEXION = "DESCONEXION";
    public static final String EXPIRACION = "EXPIRACION";
    public static final String MOVILIDAD = "MOVILIDAD";
    public static final String LIMITE_SESIONES = "LIMITE_SESIONES";

    private static final int VLAN_CUARENTENA = 999;

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final Parametros p;
    private final BaseDatos bd;
    private final Plataforma plataforma;

    private final Map<String, Sesion> porClave = new ConcurrentHashMap<>();   // dpid/puerto/mac
    private final Map<Long, Sesion> porId = new ConcurrentHashMap<>();

    public GestorSesiones(Parametros p, BaseDatos bd, Plataforma plataforma) {
        this.p = p;
        this.bd = bd;
        this.plataforma = plataforma;
    }

    /**
     * Las sesiones que quedaron abiertas en la BD ya no tienen reglas: se cierran y se rebota su
     * puerto, para que el supplicant (que se cree autorizado) vuelva a autenticarse.
     */
    public void iniciar() {
        try {
            List<String> puertos = bd.cerrarHuerfanas();
            for (String dp : puertos) {
                int i = dp.lastIndexOf('/');
                plataforma.rebotarPuerto(dp.substring(0, i), Integer.parseInt(dp.substring(i + 1)));
            }
            if (!puertos.isEmpty()) {
                log.info("M1: {} sesiones de una ejecución anterior cerradas y sus puertos rebotados",
                         puertos.size());
            }
        } catch (Exception e) {
            log.warn("M1: no se pudo limpiar las sesiones huérfanas: {}", e.getMessage());
        }
    }

    /** Al detener M1 se retiran las reglas, se cierran las sesiones y se rebotan sus puertos. */
    public synchronized void detener() {
        for (Sesion s : new ArrayList<>(porId.values())) {
            cerrar(s, DESCONEXION, true);
        }
    }

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

    public List<Sesion> sesiones() {
        return Collections.unmodifiableList(new ArrayList<>(porId.values()));
    }

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
            Sesion previa = buscarPorMac(mac);
            if (previa != null) {
                boolean mismoPuerto = previa.dpid.equals(dpid) && previa.puerto == puerto;
                if (mismoPuerto) {
                    ipPrevia = previa.ip;
                    retirarAntes.addAll(desvincular(previa));
                    bd.cerrarSesion(previa.id, DESCONEXION);
                    log.info("M1: CERRADA {} (reautenticación)", previa);
                } else {
                    cerrar(previa, MOVILIDAD, false);
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
            bd.cerrarSesionesDispositivo(s.dispositivoId, DESCONEXION);

            // Límite de sesiones simultáneas del rol (PUCP-Max-Sessions o rol.max_sesiones)
            int max = maxSesionesRadius > 0 ? maxSesionesRadius : r.maxSesiones;
            List<Long> abiertas = bd.sesionesAbiertas(uid);
            while (abiertas.size() >= max) {
                long vieja = abiertas.remove(0);
                Sesion sv = porId.get(vieja);
                if (sv != null) {
                    // Sin rebote (AVZ07, Tabla 20): rebotar haría que el equipo desplazado se
                    // reautentique solo y desplace a otra sesión, en un ciclo sin fin.
                    cerrar(sv, LIMITE_SESIONES, false);
                } else {
                    bd.cerrarSesion(vieja, LIMITE_SESIONES);
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
            if (ipPrevia != null && rol.equals(previa.rol)) {
                activar(s, ipPrevia);
            }
            return s;
        } catch (Exception e) {
            log.error("M1: error al abrir la sesión de {} en {}/{}: {}", usuario, dpid, puerto, e.toString());
            return null;
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

    /** Fase 4: retira las reglas de la sesión, la marca CERRADA y, si se pide, rebota el puerto. */
    public synchronized void cerrar(Sesion s, String motivo, boolean rebotar) {
        if (s.estado == Sesion.Estado.CERRADA) {
            return;
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
        if (rebotar) {
            plataforma.rebotarPuerto(s.dpid, s.puerto);
        }
    }

    /** EAPOL-Logoff del host. */
    public void cerrarPorLogoff(String dpid, int puerto, String mac) {
        Sesion s = buscar(dpid, puerto, mac);
        if (s != null) {
            cerrar(s, DESCONEXION, false);
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
            cerrar(s, EXPIRACION, p.rebotarPuertoAlExpirar);
        }
    }
}
