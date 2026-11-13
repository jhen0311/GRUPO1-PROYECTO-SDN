package pe.edu.pucp.tel354.m2;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.edu.pucp.tel354.m2.nucleo.Politica;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Acceso de M2 a PostgreSQL con el rol onos_m2 (solo SELECT; 02_roles.sql) y TLS (verify-ca).
 *
 * <ul>
 *   <li>{@link #cargar}: lee v_permiso_efectivo y v_acceso_curso y arma la matriz. Abre y cierra
 *       la conexión cada vez (son decenas de filas; la recarga ocurre con policy_changed o cada 30 s).</li>
 *   <li>{@link Escucha}: conexión dedicada con LISTEN policy_changed (triggers de permiso_rol, recurso
 *       e inscripcion en 01_schema.sql). Corre en su propio hilo: nunca en el de eventos de ONOS.</li>
 * </ul>
 */
final class BaseDatosM2 {

    static final String CANAL = "policy_changed";

    private BaseDatosM2() {
    }

    static Connection conectar(String url, String usuario, String clave, boolean conTiempoLimite)
            throws SQLException {
        Properties pr = new Properties();
        pr.setProperty("user", usuario);
        pr.setProperty("password", clave);
        pr.setProperty("connectTimeout", "5");
        if (conTiempoLimite) {
            pr.setProperty("socketTimeout", "10");
        }
        pr.setProperty("ApplicationName", "onos-m2");
        // Dentro de OSGi, DriverManager no ve el driver embebido: se usa directamente (como M1 y M7).
        Connection cx = new org.postgresql.Driver().connect(url, pr);
        if (cx == null) {
            throw new SQLException("URL JDBC no reconocida: " + url);
        }
        return cx;
    }

    static Politica cargar(String url, String usuario, String clave) throws SQLException {
        List<Politica.Permiso> permisos = new ArrayList<>();
        List<Object[]> cursos = new ArrayList<>();
        try (Connection c = conectar(url, usuario, clave, true); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT rol_id, rol, octeto, recurso_id, host(ip), protocolo, puerto "
                            + "FROM v_permiso_efectivo ORDER BY rol_id, recurso_id")) {
                while (rs.next()) {
                    permisos.add(new Politica.Permiso(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getInt(4),
                                                      rs.getString(5), rs.getString(6), rs.getInt(7)));
                }
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT usuario_id, host(ip), protocolo, puerto FROM v_acceso_curso")) {
                while (rs.next()) {
                    cursos.add(new Object[]{rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4)});
                }
            }
        }
        return new Politica(permisos, cursos, System.currentTimeMillis());
    }

    /**
     * Hilo de LISTEN policy_changed. Cada aviso (y cada reconexión, porque pudo perderse alguno
     * mientras estaba caída) llama a {@code alCambio}. Si la conexión falla, reintenta cada 5 s.
     */
    static final class Escucha implements Runnable {
        private final Logger log = LoggerFactory.getLogger(M2Manager.class);
        private final String url;
        private final String usuario;
        private final String clave;
        private final Runnable alCambio;
        private volatile boolean activo = true;
        private volatile boolean conectado;
        private volatile Connection actual;

        Escucha(String url, String usuario, String clave, Runnable alCambio) {
            this.url = url;
            this.usuario = usuario;
            this.clave = clave;
            this.alCambio = alCambio;
        }

        boolean conectado() {
            return conectado;
        }

        void detener() {
            activo = false;
            Connection c = actual;
            if (c != null) {
                try {
                    c.close();
                } catch (SQLException e) {
                    // se está cerrando
                }
            }
        }

        @Override
        public void run() {
            boolean avisado = false;
            while (activo) {
                try (Connection c = conectar(url, usuario, clave, false); Statement st = c.createStatement()) {
                    actual = c;
                    st.execute("LISTEN " + CANAL);
                    PGConnection pg = c.unwrap(PGConnection.class);
                    conectado = true;
                    avisado = false;
                    log.info("M2: escuchando {} en PostgreSQL", CANAL);
                    alCambio.run();                          // lo que cambió mientras no se escuchaba
                    while (activo) {
                        PGNotification[] n = pg.getNotifications(5000);
                        if (n != null && n.length > 0) {
                            log.info("M2: {} ({} avisos, tablas {}): se recarga la matriz", CANAL, n.length,
                                     tablas(n));
                            alCambio.run();
                        }
                    }
                } catch (SQLException e) {
                    conectado = false;
                    if (activo && !avisado) {
                        log.warn("M2: sin LISTEN {} ({}); se reintenta cada 5 s y la matriz se revisa "
                                         + "igual en la conciliación periódica", CANAL, e.toString());
                        avisado = true;
                    }
                } finally {
                    conectado = false;
                    actual = null;
                }
                dormir(5000);
            }
        }

        private static String tablas(PGNotification[] n) {
            java.util.Set<String> s = new java.util.TreeSet<>();
            for (PGNotification x : n) {
                s.add(x.getParameter());
            }
            return s.toString();
        }

        private void dormir(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                activo = false;
            }
        }
    }
}
