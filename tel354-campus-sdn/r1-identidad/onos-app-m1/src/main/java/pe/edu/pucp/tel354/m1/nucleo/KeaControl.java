package pe.edu.pucp.tel354.m1.nucleo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Control Agent de Kea (LLSD R3, sección 5.2): lease4-del de la IP de una sesión en el paso
 * forzado a cuarentena, para que Kea responda DHCPNAK a la IP anterior.
 */
public class KeaControl {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final String url;

    public KeaControl(String url) {
        this.url = url;
    }

    /** @return true si Kea respondió result = 0 (borrado) o 3 (no existía). */
    public boolean borrarLease(String ip) {
        String cuerpo = "{\"command\": \"lease4-del\", \"service\": [\"dhcp4\"], "
                + "\"arguments\": {\"ip-address\": \"" + ip + "\"}}";
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) {
                o.write(cuerpo.getBytes(StandardCharsets.UTF_8));
            }
            String r;
            try (InputStream in = c.getInputStream()) {
                r = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            boolean ok = r.contains("\"result\": 0") || r.contains("\"result\":0")
                    || r.contains("\"result\": 3") || r.contains("\"result\":3");
            log.info("M1: lease4-del {} -> {}", ip, r.replaceAll("\\s+", " "));
            return ok;
        } catch (Exception e) {
            log.warn("M1: lease4-del {} falló ({}): Kea responderá DHCPNAK al vencer el lease", ip, e.toString());
            return false;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }
}
