package pe.edu.pucp.tel354.m1.nucleo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Acceso de M1 a PostgreSQL con el rol onos_m1 (AVZ07, sección 3.2): lee usuario, rol,
 * switch_acceso y v_acceso_curso; escribe dispositivo y sesion.
 * Una sola conexión, reabierta si se cae; los métodos están sincronizados.
 * En el clúster, la BD es la fuente común: la instancia que gana la maestría de un switch
 * reconstruye sus sesiones con {@link #sesionesAbiertasEn(String)}.
 */
public class BaseDatos {

    /** Fila de la tabla rol. */
    public static final class Rol {
        public final int id;
        public final int octeto;
        public final int maxSesiones;

        Rol(int id, int octeto, int maxSesiones) {
            this.id = id;
            this.octeto = octeto;
            this.maxSesiones = maxSesiones;
        }
    }

    private final Parametros p;
    private Connection cx;

    public BaseDatos(Parametros p) {
        this.p = p;
    }

    private Connection cx() throws SQLException {
        if (cx == null || cx.isClosed() || !cx.isValid(2)) {
            Properties pr = new Properties();
            pr.setProperty("user", p.jdbcUsuario);
            pr.setProperty("password", p.jdbcClave);
            pr.setProperty("connectTimeout", "5");
            pr.setProperty("ApplicationName", "onos-m1");
            // Se usa el Driver directamente: dentro de OSGi, DriverManager no ve el driver embebido.
            cx = new org.postgresql.Driver().connect(p.jdbcUrl, pr);
            if (cx == null) {
                throw new SQLException("URL JDBC no reconocida: " + p.jdbcUrl);
            }
            cx.setAutoCommit(true);
        }
        return cx;
    }

    public synchronized void cerrar() {
        try {
            if (cx != null) {
                cx.close();
            }
        } catch (SQLException e) {
            // nada
        }
        cx = null;
    }

    public synchronized Integer usuarioId(String uid) throws SQLException {
        try (PreparedStatement st = cx().prepareStatement(
                "SELECT id FROM usuario WHERE uid_ldap = ? AND estado = 'ACTIVO'")) {
            st.setString(1, uid);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    public synchronized Rol rol(String nombre) throws SQLException {
        try (PreparedStatement st = cx().prepareStatement(
                "SELECT id, octeto, max_sesiones FROM rol WHERE nombre = ?")) {
            st.setString(1, nombre);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? new Rol(rs.getInt(1), rs.getInt(2), rs.getInt(3)) : null;
            }
        }
    }

    /** Pabellón (N) del switch de acceso, o null si el dpid no es de acceso. */
    public synchronized Integer pabellon(String dpid) throws SQLException {
        try (PreparedStatement st = cx().prepareStatement(
                "SELECT pabellon_id FROM switch_acceso WHERE dpid = ?")) {
            st.setString(1, dpid);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    public synchronized int upsertDispositivo(String mac, String dpid, int puerto) throws SQLException {
        try (PreparedStatement st = cx().prepareStatement(
                "INSERT INTO dispositivo (mac, switch_dpid, puerto) VALUES (?::macaddr, ?, ?) "
                        + "ON CONFLICT (mac) DO UPDATE SET switch_dpid = EXCLUDED.switch_dpid, "
                        + "puerto = EXCLUDED.puerto RETURNING id")) {
            st.setString(1, mac);
            st.setString(2, dpid);
            st.setInt(3, puerto);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Ids de las sesiones abiertas del usuario, de la más antigua a la más reciente. */
    public synchronized List<Long> sesionesAbiertas(int usuarioId) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement st = cx().prepareStatement(
                "SELECT id FROM sesion WHERE usuario_id = ? AND estado <> 'CERRADA' ORDER BY inicio")) {
            st.setInt(1, usuarioId);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    public synchronized void cerrarSesion(long id, String motivo) throws SQLException {
        try (PreparedStatement st = cx().prepareStatement(
                "UPDATE sesion SET estado = 'CERRADA', fin = now(), motivo_cierre = ? "
                        + "WHERE id = ? AND estado <> 'CERRADA'")) {
            st.setString(1, motivo);
            st.setLong(2, id);
            st.executeUpdate();
        }
    }

    /** Una sola sesión abierta por equipo: cierra las anteriores del dispositivo. */
    public synchronized int cerrarSesionesDispositivo(int dispositivoId, String motivo) throws SQLException {
        try (PreparedStatement st = cx().prepareStatement(
                "UPDATE sesion SET estado = 'CERRADA', fin = now(), motivo_cierre = ? "
                        + "WHERE dispositivo_id = ? AND estado <> 'CERRADA'")) {
            st.setString(1, motivo);
            st.setInt(2, dispositivoId);
            return st.executeUpdate();
        }
    }

    public synchronized long abrirSesion(Sesion s) throws SQLException {
        try (PreparedStatement st = cx().prepareStatement(
                "INSERT INTO sesion (usuario_id, dispositivo_id, switch_dpid, puerto, rol_efectivo_id, "
                        + "vlan_id, estado) VALUES (?, ?, ?, ?, ?, ?, 'AUTENTICADA') RETURNING id")) {
            st.setInt(1, s.usuarioId);
            st.setInt(2, s.dispositivoId);
            st.setString(3, s.dpid);
            st.setInt(4, s.puerto);
            st.setInt(5, s.rolId);
            st.setInt(6, s.vlan);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public synchronized void activarSesion(long id, String ip) throws SQLException {
        try (PreparedStatement st = cx().prepareStatement(
                "UPDATE sesion SET ip = ?::inet, estado = 'ACTIVA' WHERE id = ?")) {
            st.setString(1, ip);
            st.setLong(2, id);
            st.executeUpdate();
        }
    }

    /** Servidores de los cursos del usuario en el ciclo vigente (reglas 110). */
    public synchronized List<Plataforma.AccesoCurso> accesosCurso(String uid) throws SQLException {
        List<Plataforma.AccesoCurso> l = new ArrayList<>();
        try (PreparedStatement st = cx().prepareStatement(
                "SELECT curso, host(ip), protocolo, puerto FROM v_acceso_curso WHERE uid_ldap = ? "
                        + "ORDER BY 1, 2, 4")) {
            st.setString(1, uid);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    l.add(new Plataforma.AccesoCurso(rs.getString(1), rs.getString(2),
                                                     rs.getString(3), rs.getInt(4)));
                }
            }
        }
        return l;
    }

    /** Sesión leída de la BD con los datos que M1 necesita para reconstruirla o informarla. */
    public static final class FilaSesion {
        public long id;
        public String dpid;
        public int puerto;
        public String mac;
        public String usuario;
        public int usuarioId;
        public String rol;
        public int rolId;
        public int octeto;
        public int pabellon;
        public int vlan;
        public String ip;
        public String estado;
        public int dispositivoId;
        public long inicioMs;

        public Sesion aSesion() {
            Sesion s = new Sesion();
            s.id = id;
            s.dpid = dpid;
            s.puerto = puerto;
            s.mac = mac;
            s.usuario = usuario;
            s.usuarioId = usuarioId;
            s.rol = rol;
            s.rolId = rolId;
            s.octeto = octeto;
            s.pabellon = pabellon;
            s.vlan = vlan;
            s.ip = ip;
            s.dispositivoId = dispositivoId;
            s.inicioMs = inicioMs;
            s.estado = "ACTIVA".equals(estado) ? Sesion.Estado.ACTIVA
                    : "CERRADA".equals(estado) ? Sesion.Estado.CERRADA : Sesion.Estado.AUTENTICADA;
            return s;
        }
    }

    private static final String SEL_SESION =
            "SELECT s.id, s.switch_dpid, s.puerto, d.mac::text, u.uid_ldap, u.id, r.nombre, r.id, r.octeto, "
            + "COALESCE(sa.pabellon_id, 0), COALESCE(s.vlan_id, 0), host(s.ip), s.estado, s.dispositivo_id, "
            + "(EXTRACT(EPOCH FROM s.inicio) * 1000)::bigint "
            + "FROM sesion s JOIN usuario u ON u.id = s.usuario_id JOIN rol r ON r.id = s.rol_efectivo_id "
            + "JOIN dispositivo d ON d.id = s.dispositivo_id LEFT JOIN switch_acceso sa ON sa.dpid = s.switch_dpid ";

    private List<FilaSesion> filas(String where, Object arg) throws SQLException {
        List<FilaSesion> l = new ArrayList<>();
        try (PreparedStatement st = cx().prepareStatement(SEL_SESION + where)) {
            if (arg == null) {
                // sin parámetro
            } else if (arg instanceof Long) {
                st.setLong(1, (Long) arg);
            } else if (arg instanceof Integer) {
                st.setInt(1, (Integer) arg);
            } else {
                st.setString(1, (String) arg);
            }
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    FilaSesion f = new FilaSesion();
                    f.id = rs.getLong(1);
                    f.dpid = rs.getString(2);
                    f.puerto = rs.getInt(3);
                    f.mac = rs.getString(4).toLowerCase();
                    f.usuario = rs.getString(5);
                    f.usuarioId = rs.getInt(6);
                    f.rol = rs.getString(7);
                    f.rolId = rs.getInt(8);
                    f.octeto = rs.getInt(9);
                    f.pabellon = rs.getInt(10);
                    f.vlan = rs.getInt(11);
                    f.ip = rs.getString(12);
                    f.estado = rs.getString(13);
                    f.dispositivoId = rs.getInt(14);
                    f.inicioMs = rs.getLong(15);
                    l.add(f);
                }
            }
        }
        return l;
    }

    /** Sesiones abiertas de un switch de acceso (reconstrucción al ganar la maestría). */
    public synchronized List<FilaSesion> sesionesAbiertasEn(String dpid) throws SQLException {
        return filas("WHERE s.estado <> 'CERRADA' AND s.switch_dpid = ? ORDER BY s.id", dpid);
    }

    /** Todas las sesiones abiertas del campus (consola de TI). */
    public synchronized List<FilaSesion> sesionesAbiertasTodas() throws SQLException {
        return filas("WHERE s.estado <> 'CERRADA' ORDER BY s.id", null);
    }

    /** Sesiones abiertas de un usuario en todo el campus. */
    public synchronized List<FilaSesion> sesionesAbiertasDeUsuario(int usuarioId) throws SQLException {
        return filas("WHERE s.estado <> 'CERRADA' AND s.usuario_id = ? ORDER BY s.id", usuarioId);
    }

    /** Sesiones abiertas de un equipo (MAC) en todo el campus. */
    public synchronized List<FilaSesion> sesionesAbiertasDeMac(String mac) throws SQLException {
        return filas("WHERE s.estado <> 'CERRADA' AND d.mac = ?::macaddr ORDER BY s.id", mac);
    }

    /** Sesión abierta con esa IP, o null. */
    public synchronized FilaSesion sesionAbiertaPorIp(String ip) throws SQLException {
        List<FilaSesion> l = filas("WHERE s.estado = 'ACTIVA' AND s.ip = ?::inet ORDER BY s.id DESC LIMIT 1", ip);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Sesión por id (también cerrada), o null. */
    public synchronized FilaSesion sesion(long id) throws SQLException {
        List<FilaSesion> l = filas("WHERE s.id = ?", id);
        return l.isEmpty() ? null : l.get(0);
    }
}
