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

    /**
     * Al arrancar M1 no hay reglas de sesión en los switches: las sesiones abiertas quedan huérfanas.
     * Devuelve los puertos ("dpid/puerto") que tenían sesión, para rebotarlos.
     */
    public synchronized List<String> cerrarHuerfanas() throws SQLException {
        List<String> puertos = new ArrayList<>();
        try (PreparedStatement st = cx().prepareStatement(
                "UPDATE sesion SET estado = 'CERRADA', fin = now(), motivo_cierre = 'DESCONEXION' "
                        + "WHERE estado <> 'CERRADA' RETURNING switch_dpid, puerto")) {
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    puertos.add(rs.getString(1) + "/" + rs.getInt(2));
                }
            }
        }
        return puertos;
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
}
