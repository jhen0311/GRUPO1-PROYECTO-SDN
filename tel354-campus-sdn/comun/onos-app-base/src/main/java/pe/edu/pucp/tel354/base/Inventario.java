package pe.edu.pucp.tel354.base;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Lectura de switch_acceso (dpid → pabellón) con el rol onos_m7 (solo SELECT sobre esa tabla).
 * Una consulta por conciliación (cada 30 s): se abre y se cierra la conexión cada vez.
 */
public final class Inventario {

    private Inventario() {
    }

    public static Map<String, Integer> pabellones(String url, String usuario, String clave) throws SQLException {
        Properties pr = new Properties();
        pr.setProperty("user", usuario);
        pr.setProperty("password", clave);
        pr.setProperty("connectTimeout", "5");
        pr.setProperty("ApplicationName", "onos-m7");
        // Dentro de OSGi, DriverManager no ve el driver embebido: se usa directamente.
        Connection cx = new org.postgresql.Driver().connect(url, pr);
        if (cx == null) {
            throw new SQLException("URL JDBC no reconocida: " + url);
        }
        Map<String, Integer> m = new HashMap<>();
        try (Connection c = cx; Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT dpid, pabellon_id FROM switch_acceso")) {
            while (rs.next()) {
                m.put(rs.getString(1).trim(), rs.getInt(2));
            }
        }
        return m;
    }
}
