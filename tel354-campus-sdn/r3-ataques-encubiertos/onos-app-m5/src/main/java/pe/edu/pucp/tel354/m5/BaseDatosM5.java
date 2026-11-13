package pe.edu.pucp.tel354.m5;

import pe.edu.pucp.tel354.api.r3.Criticidad;
import pe.edu.pucp.tel354.api.r3.EstadoIncidente;
import pe.edu.pucp.tel354.api.r3.Filtro;
import pe.edu.pucp.tel354.api.r3.Incidente;
import pe.edu.pucp.tel354.api.r3.Nivel;
import pe.edu.pucp.tel354.api.r3.NivelAtaque;
import pe.edu.pucp.tel354.api.r3.TipoAtaque;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Acceso de M5 a PostgreSQL con el rol onos_m5 (LLSD R3, secciones 6.2 y 6.3): escribe incidente
 * y cuarentena; lee sesion, usuario y recurso. Una sola conexión, reabierta si se cae.
 */
public class BaseDatosM5 {

    /** Conteos para la reincidencia (sin contar el incidente actual). */
    public static final class Recuento {
        public int previos;
        public int fuertes;
        public boolean vigentes;
    }

    private final String url;
    private final String usuario;
    private final String clave;
    private Connection cx;

    public BaseDatosM5(String url, String usuario, String clave) {
        this.url = url;
        this.usuario = usuario;
        this.clave = clave;
    }

    private Connection cx() throws SQLException {
        if (cx == null || cx.isClosed() || !cx.isValid(2)) {
            Properties pr = new Properties();
            pr.setProperty("user", usuario);
            pr.setProperty("password", clave);
            pr.setProperty("connectTimeout", "5");
            pr.setProperty("ApplicationName", "onos-m5");
            // Dentro de OSGi, DriverManager no ve el driver embebido: se usa directamente.
            cx = new org.postgresql.Driver().connect(url, pr);
            if (cx == null) {
                throw new SQLException("URL JDBC no reconocida: " + url);
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

    /** INSERT … RETURNING id: el id es la base de la cookie 0x06. */
    public synchronized long insertar(long sesionId, TipoAtaque tipo, String fuente, NivelAtaque severidad,
                                      Nivel accion, String evidencia, long expiraMs) throws SQLException {
        try (PreparedStatement st = cx().prepareStatement(
                "INSERT INTO incidente (sesion_id, requisito, tipo_ataque, fuente, severidad, accion, evidencia, "
                        + "expira_en) VALUES (?, 'R3', ?, ?, ?, ?, ?::jsonb, ?) RETURNING id")) {
            st.setLong(1, sesionId);
            st.setString(2, tipo.name());
            st.setString(3, fuente);
            st.setString(4, severidad.name());
            st.setString(5, accion.name());
            st.setString(6, evidencia);
            st.setTimestamp(7, expiraMs > 0 ? new Timestamp(expiraMs) : null);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public synchronized void actualizar(long id, Nivel accion, String evidencia, long expiraMs) throws SQLException {
        try (PreparedStatement st = cx().prepareStatement(
                "UPDATE incidente SET accion = ?, evidencia = ?::jsonb, expira_en = ? WHERE id = ?")) {
            st.setString(1, accion.name());
            st.setString(2, evidencia);
            st.setTimestamp(3, expiraMs > 0 ? new Timestamp(expiraMs) : null);
            st.setLong(4, id);
            st.executeUpdate();
        }
    }

    /** Cierre sin sanción: descarte de TI o vencimiento (evidencia.cierre). */
    public synchronized boolean mitigar(long id, String cierre, String solicitante, String motivo) throws SQLException {
        try (PreparedStatement st = cx().prepareStatement(
                "UPDATE incidente SET mitigado_en = now(), expira_en = LEAST(expira_en, now()), "
                        + "evidencia = evidencia || jsonb_build_object('cierre', ?::text, 'solicitante', ?::text, "
                        + "'motivo', ?::text) WHERE id = ? AND mitigado_en IS NULL")) {
            st.setString(1, cierre);
            st.setString(2, solicitante);
            st.setString(3, motivo);
            st.setLong(4, id);
            return st.executeUpdate() > 0;
        }
    }

    /** Confirmación: fila de cuarentena y accion = N3, en una transacción. @return fin de la sanción (ms) */
    public synchronized long confirmar(long id, int usuarioId, long sancionSeg, String solicitante, String motivo)
            throws SQLException {
        Connection c = cx();
        c.setAutoCommit(false);
        try {
            long fin;
            try (PreparedStatement st = c.prepareStatement(
                    "INSERT INTO cuarentena (usuario_id, incidente_id, inicio, fin) "
                            + "VALUES (?, ?, now(), now() + make_interval(secs => ?)) RETURNING fin")) {
                st.setInt(1, usuarioId);
                st.setLong(2, id);
                st.setLong(3, sancionSeg);
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    fin = rs.getTimestamp(1).getTime();
                }
            }
            try (PreparedStatement st = c.prepareStatement(
                    "UPDATE incidente SET accion = 'N3', expira_en = LEAST(expira_en, now()), "
                            + "evidencia = evidencia || jsonb_build_object('cierre', 'CONFIRMACION', "
                            + "'solicitante', ?::text, 'motivo', ?::text) WHERE id = ?")) {
                st.setString(1, solicitante);
                st.setString(2, motivo);
                st.setLong(3, id);
                st.executeUpdate();
            }
            c.commit();
            return fin;
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    /** Incidentes del usuario en 24 h (sin el actual), los Fuerte y si hay contenciones vigentes. */
    public synchronized Recuento recuento(int usuarioId, long excluirId) throws SQLException {
        Recuento r = new Recuento();
        try (PreparedStatement st = cx().prepareStatement(
                "SELECT count(*), count(*) FILTER (WHERE i.severidad = 'Fuerte'), "
                        + "bool_or(i.expira_en > now() AND i.mitigado_en IS NULL AND i.accion IN ('N1','N2')) "
                        + "FROM incidente i JOIN sesion s ON s.id = i.sesion_id "
                        + "WHERE s.usuario_id = ? AND i.requisito = 'R3' AND i.id <> ? "
                        + "AND i.detectado_en > now() - interval '24 hours'")) {
            st.setInt(1, usuarioId);
            st.setLong(2, excluirId);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                r.previos = rs.getInt(1);
                r.fuertes = rs.getInt(2);
                r.vigentes = rs.getBoolean(3);
            }
        }
        return r;
    }

    /** Servicios inventariados "ip/PROTO/puerto" (excepción del ataque coordinado). */
    public synchronized Set<String> servicios() throws SQLException {
        Set<String> s = new HashSet<>();
        try (PreparedStatement st = cx().prepareStatement("SELECT host(ip), protocolo, puerto FROM recurso");
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                s.add(rs.getString(1) + "/" + rs.getString(2) + "/" + rs.getInt(3));
            }
        }
        return s;
    }

    private static final String SEL =
            "SELECT i.id, i.sesion_id, s.usuario_id, u.uid_ldap, i.tipo_ataque, i.severidad, i.accion, "
            + "i.evidencia::text, (EXTRACT(EPOCH FROM i.detectado_en) * 1000)::bigint, "
            + "COALESCE((EXTRACT(EPOCH FROM i.expira_en) * 1000)::bigint, 0), "
            + "COALESCE((EXTRACT(EPOCH FROM i.mitigado_en) * 1000)::bigint, 0), "
            + "CASE WHEN q.id IS NOT NULL THEN 'CONFIRMADO' "
            + "     WHEN i.mitigado_en IS NOT NULL AND i.evidencia->>'cierre' = 'VENCIMIENTO' THEN 'VENCIDO' "
            + "     WHEN i.mitigado_en IS NOT NULL THEN 'DESCARTADO' ELSE 'EN_INVESTIGACION' END, "
            + "COALESCE(i.evidencia->>'criticidad', 'MAYOR'), COALESCE(i.evidencia->>'nivel_calculado', i.accion), "
            + "COALESCE((i.evidencia->>'revision_prioritaria')::boolean, false) "
            + "FROM incidente i JOIN sesion s ON s.id = i.sesion_id JOIN usuario u ON u.id = s.usuario_id "
            + "LEFT JOIN cuarentena q ON q.incidente_id = i.id WHERE i.requisito = 'R3' ";

    private static Incidente fila(ResultSet rs) throws SQLException {
        return Incidente.builder().id(rs.getLong(1)).sesionId(rs.getLong(2)).usuarioId(rs.getInt(3))
                .usuario(rs.getString(4)).tipo(TipoAtaque.valueOf(rs.getString(5)))
                .nivelAtaque(NivelAtaque.valueOf(rs.getString(6))).nivel(Nivel.valueOf(rs.getString(7)))
                .evidencia(rs.getString(8)).detectadoMs(rs.getLong(9)).expiraMs(rs.getLong(10))
                .mitigadoMs(rs.getLong(11)).estado(EstadoIncidente.valueOf(rs.getString(12)))
                .criticidad(Criticidad.valueOf(rs.getString(13))).nivelCalculado(Nivel.valueOf(rs.getString(14)))
                .revisionPrioritaria(rs.getBoolean(15)).build();
    }

    public synchronized List<Incidente> listar(Filtro f) throws SQLException {
        StringBuilder q = new StringBuilder(SEL);
        List<Object> args = new ArrayList<>();
        if (f.soloAbiertos()) {
            q.append("AND q.id IS NULL AND i.mitigado_en IS NULL ");
        }
        if (f.usuarioId() != null) {
            q.append("AND s.usuario_id = ? ");
            args.add(f.usuarioId());
        }
        if (f.usuario() != null) {
            q.append("AND u.uid_ldap = ? ");
            args.add(f.usuario());
        }
        q.append("ORDER BY i.id DESC LIMIT ?");
        args.add(f.limite());
        List<Incidente> l = new ArrayList<>();
        try (PreparedStatement st = cx().prepareStatement(q.toString())) {
            for (int k = 0; k < args.size(); k++) {
                Object a = args.get(k);
                if (a instanceof Integer) {
                    st.setInt(k + 1, (Integer) a);
                } else {
                    st.setString(k + 1, (String) a);
                }
            }
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    l.add(fila(rs));
                }
            }
        }
        return l;
    }

    public synchronized Incidente detalle(long id) throws SQLException {
        try (PreparedStatement st = cx().prepareStatement(SEL + "AND i.id = ?")) {
            st.setLong(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? fila(rs) : null;
            }
        }
    }

    /** Incidentes abiertos con contención vigente (al arrancar y para reconexiones). */
    public synchronized List<Incidente> vigentes(Integer usuarioId) throws SQLException {
        String q = SEL + "AND q.id IS NULL AND i.mitigado_en IS NULL AND i.accion IN ('N1','N2') "
                + "AND i.expira_en > now() " + (usuarioId != null ? "AND s.usuario_id = ? " : "") + "ORDER BY i.id";
        List<Incidente> l = new ArrayList<>();
        try (PreparedStatement st = cx().prepareStatement(q)) {
            if (usuarioId != null) {
                st.setInt(1, usuarioId);
            }
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    l.add(fila(rs));
                }
            }
        }
        return l;
    }

    /** Incidentes abiertos cuyo plazo venció sin que nadie los cerrara (p. ej., con M5 detenido). */
    public synchronized int cerrarVencidos() throws SQLException {
        try (PreparedStatement st = cx().prepareStatement(
                "UPDATE incidente i SET mitigado_en = now(), "
                        + "evidencia = evidencia || '{\"cierre\": \"VENCIMIENTO\"}'::jsonb "
                        + "WHERE requisito = 'R3' AND mitigado_en IS NULL AND expira_en < now() "
                        + "AND NOT EXISTS (SELECT 1 FROM cuarentena q WHERE q.incidente_id = i.id)")) {
            return st.executeUpdate();
        }
    }
}
