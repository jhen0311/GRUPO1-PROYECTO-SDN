package pe.edu.pucp.tel354.m2.nucleo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Matriz de autorización de R2 en memoria (sin dependencias de ONOS: se prueba con probar_m2.sh).
 *
 * <ul>
 *   <li>Permisos por rol: filas de v_permiso_efectivo (rol con herencia → recurso). Cada fila es una
 *       regla 100 en la Tabla 1 de cada switch de acceso.</li>
 *   <li>Acceso por curso: filas de v_acceso_curso (usuario inscrito → servidor del curso). Esas
 *       reglas 110 las instala M1 al activar cada sesión; M2 solo las usa para resolver la carrera
 *       con un cambio de inscripción.</li>
 * </ul>
 *
 * Es inmutable: cada recarga (policy_changed o la revisión periódica) construye una nueva y se
 * reemplaza de una vez, así el slow-path nunca ve una matriz a medio cargar.
 */
public final class Politica {

    /** Resultado de consultar la matriz para una cuádrupla. */
    public enum Decision { ROL, CURSO, NINGUNA }

    public static final int TCP = 6;
    public static final int UDP = 17;

    /** Una fila de v_permiso_efectivo. */
    public static final class Permiso {
        public final int rolId;
        public final String rol;
        public final int octeto;
        public final int recursoId;
        public final String ip;
        public final int proto;
        public final int puerto;

        public Permiso(int rolId, String rol, int octeto, int recursoId, String ip, String protocolo, int puerto) {
            this.rolId = rolId;
            this.rol = rol;
            this.octeto = octeto;
            this.recursoId = recursoId;
            this.ip = ip;
            this.proto = proto(protocolo);
            this.puerto = puerto;
        }

        /** Código del rol k (b = 16·k): índice de la cookie de la regla 100. */
        public int codigoRol() {
            return octeto / 16;
        }
    }

    private final List<Permiso> permisos;
    /** octeto del rol → servicios permitidos (clave ip|proto|puerto). */
    private final Map<Integer, Set<Long>> porOcteto = new HashMap<>();
    /** usuario.id → servidores de sus cursos (clave ip|proto|puerto). */
    private final Map<Integer, Set<Long>> porUsuario = new HashMap<>();
    private final int filasCurso;
    public final long cargadaMs;

    /**
     * @param permisos filas de v_permiso_efectivo
     * @param cursos   filas de v_acceso_curso como {usuarioId, ip, protocolo, puerto}
     */
    public Politica(List<Permiso> permisos, List<Object[]> cursos, long cargadaMs) {
        this.permisos = Collections.unmodifiableList(new ArrayList<>(permisos));
        for (Permiso p : permisos) {
            porOcteto.computeIfAbsent(p.octeto, k -> new HashSet<>()).add(clave(ipv4(p.ip), p.proto, p.puerto));
        }
        for (Object[] c : cursos) {
            int usuario = ((Number) c[0]).intValue();
            long k = clave(ipv4((String) c[1]), proto((String) c[2]), ((Number) c[3]).intValue());
            porUsuario.computeIfAbsent(usuario, x -> new HashSet<>()).add(k);
        }
        this.filasCurso = cursos.size();
        this.cargadaMs = cargadaMs;
    }

    public List<Permiso> permisos() {
        return permisos;
    }

    public int filasCurso() {
        return filasCurso;
    }

    /**
     * ¿La matriz permite este acceso? Primero por rol (regla 100), luego por inscripción (regla 110).
     * ICMP y otros protocolos sin puerto nunca están en la matriz (recurso solo admite TCP y UDP).
     */
    public Decision decidir(int octeto, int usuarioId, int ipDst, int proto, int puerto) {
        if (puerto < 0) {
            return Decision.NINGUNA;
        }
        long k = clave(ipDst, proto, puerto);
        if (porOcteto.getOrDefault(octeto, Collections.emptySet()).contains(k)) {
            return Decision.ROL;
        }
        if (porUsuario.getOrDefault(usuarioId, Collections.emptySet()).contains(k)) {
            return Decision.CURSO;
        }
        return Decision.NINGUNA;
    }

    /** Misma matriz (para no reprogramar ni registrar nada si la recarga no cambió nada). */
    public boolean mismoContenido(Politica o) {
        return o != null && porOcteto.equals(o.porOcteto) && porUsuario.equals(o.porUsuario)
                && permisos.size() == o.permisos.size();
    }

    // ------------------------------------------------------------------ utilidades

    public static long clave(int ip, int proto, int puerto) {
        return ((ip & 0xffffffffL) << 24) | ((proto & 0xffL) << 16) | (puerto & 0xffff);
    }

    public static int proto(String protocolo) {
        return "UDP".equalsIgnoreCase(protocolo) ? UDP : TCP;
    }

    /** "10.200.1.10" (o "10.200.1.10/32") → entero. */
    public static int ipv4(String s) {
        String ip = s.contains("/") ? s.substring(0, s.indexOf('/')) : s;
        String[] p = ip.trim().split("\\.");
        if (p.length != 4) {
            throw new IllegalArgumentException("IPv4 no válida: " + s);
        }
        int v = 0;
        for (String x : p) {
            int o = Integer.parseInt(x);
            if (o < 0 || o > 255) {
                throw new IllegalArgumentException("IPv4 no válida: " + s);
            }
            v = (v << 8) | o;
        }
        return v;
    }
}
