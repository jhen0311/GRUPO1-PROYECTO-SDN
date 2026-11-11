package pe.edu.pucp.tel354.api.sesion;

import org.onlab.packet.Ip4Address;
import org.onlab.packet.MacAddress;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;

import java.util.Objects;

/** Vista inmutable de una sesión de M1 que reciben los demás módulos. */
public final class SesionInfo {

    private final long id;
    private final int usuarioId;
    private final String usuario;
    private final String rol;
    private final int octeto;
    private final int pabellon;
    private final MacAddress mac;
    private final DeviceId dispositivo;
    private final PortNumber puerto;
    private final Ip4Address ip;
    private final int vlan;
    private final EstadoSesion estado;
    private final long inicioMs;

    private SesionInfo(Builder b) {
        this.id = b.id;
        this.usuarioId = b.usuarioId;
        this.usuario = b.usuario;
        this.rol = b.rol;
        this.octeto = b.octeto;
        this.pabellon = b.pabellon;
        this.mac = b.mac;
        this.dispositivo = b.dispositivo;
        this.puerto = b.puerto;
        this.ip = b.ip;
        this.vlan = b.vlan;
        this.estado = b.estado;
        this.inicioMs = b.inicioMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** sesion.id (base de la cookie 0x02); -1 si aún no hay sesión (AUTH_FAILED). */
    public long id() {
        return id;
    }

    public int usuarioId() {
        return usuarioId;
    }

    /** Identidad interna (uid_ldap). */
    public String usuario() {
        return usuario;
    }

    /** Rol efectivo (Filter-Id): ALUMNO, JP, DOCENTE, ADMINISTRATIVO, TI, INVITADO o CUARENTENA. */
    public String rol() {
        return rol;
    }

    /** b = 16·k. */
    public int octeto() {
        return octeto;
    }

    public int codigoRol() {
        return octeto / 16;
    }

    public int pabellon() {
        return pabellon;
    }

    public MacAddress mac() {
        return mac;
    }

    public DeviceId dispositivo() {
        return dispositivo;
    }

    public PortNumber puerto() {
        return puerto;
    }

    public ConnectPoint puntoDeConexion() {
        return new ConnectPoint(dispositivo, puerto);
    }

    /** IP concedida por Kea; null mientras la sesión está AUTENTICADA. */
    public Ip4Address ip() {
        return ip;
    }

    public int vlan() {
        return vlan;
    }

    public EstadoSesion estado() {
        return estado;
    }

    public long inicioMs() {
        return inicioMs;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SesionInfo && ((SesionInfo) o).id == id && Objects.equals(((SesionInfo) o).estado, estado)
                && Objects.equals(((SesionInfo) o).ip, ip);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, estado, ip);
    }

    @Override
    public String toString() {
        return "sesion " + id + " [" + usuario + " " + rol + " " + mac + " en " + dispositivo + "/" + puerto
                + (ip != null ? " IP " + ip : "") + " VLAN " + vlan + " " + estado + "]";
    }

    public static final class Builder {
        private long id = -1;
        private int usuarioId;
        private String usuario;
        private String rol;
        private int octeto;
        private int pabellon;
        private MacAddress mac;
        private DeviceId dispositivo;
        private PortNumber puerto;
        private Ip4Address ip;
        private int vlan;
        private EstadoSesion estado = EstadoSesion.AUTENTICADA;
        private long inicioMs = System.currentTimeMillis();

        public Builder id(long v) {
            id = v;
            return this;
        }

        public Builder usuarioId(int v) {
            usuarioId = v;
            return this;
        }

        public Builder usuario(String v) {
            usuario = v;
            return this;
        }

        public Builder rol(String v) {
            rol = v;
            return this;
        }

        public Builder octeto(int v) {
            octeto = v;
            return this;
        }

        public Builder pabellon(int v) {
            pabellon = v;
            return this;
        }

        public Builder mac(MacAddress v) {
            mac = v;
            return this;
        }

        public Builder dispositivo(DeviceId v) {
            dispositivo = v;
            return this;
        }

        public Builder puerto(PortNumber v) {
            puerto = v;
            return this;
        }

        public Builder ip(Ip4Address v) {
            ip = v;
            return this;
        }

        public Builder vlan(int v) {
            vlan = v;
            return this;
        }

        public Builder estado(EstadoSesion v) {
            estado = v;
            return this;
        }

        public Builder inicioMs(long v) {
            inicioMs = v;
            return this;
        }

        public SesionInfo build() {
            return new SesionInfo(this);
        }
    }
}
