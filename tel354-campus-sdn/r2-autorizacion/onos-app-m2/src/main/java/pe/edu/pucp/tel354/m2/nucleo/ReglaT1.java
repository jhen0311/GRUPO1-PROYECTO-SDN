package pe.edu.pucp.tel354.m2.nucleo;

/**
 * Descripción de una regla de la Tabla 1 (autorización) sin dependencias de ONOS; el adaptador
 * (Traductor) la convierte en FlowRule. Así el conjunto se cuenta y se prueba sin el controlador.
 *
 * Cookie (LLSD R3, Tabla 7): appId de M2 | tipo | id | índice
 * <ul>
 *   <li>0x03 rol: id = recurso.id, índice = código del rol k (reglas 100)</li>
 *   <li>0x04 común: id = prioridad·2^16 + grupo, índice 0 (reglas 60, 58, 57, 55, 50 y 0)</li>
 * </ul>
 */
public final class ReglaT1 {

    /** Tipo de la cookie (mismos valores que pe.edu.pucp.tel354.api.Cookies). */
    public static final int TIPO_ROL = 0x03;
    public static final int TIPO_COMUN = 0x04;

    /** Meter que aplica la regla (lo resuelve el adaptador con los meters del switch). */
    public enum Meter { NINGUNO, CAPTURA, CUARENTENA }

    public final int tipo;
    public final int prioridad;
    public final long id;
    public final int indice;

    // Coincidencia (null = no se usa). ethType IPv4 va implícito si hay cualquier campo IP.
    public boolean ipv4;
    public Integer vlan;
    public String ipOrigen;
    public String ipDestino;
    public Integer proto;
    public Integer puertoDestino;

    // Acciones, en este orden: meter, salida al controlador o salto de tabla (ninguna = drop)
    public Meter meter = Meter.NINGUNO;
    public boolean controlador;
    public Integer irATabla;

    private ReglaT1(int tipo, int prioridad, long id, int indice) {
        this.tipo = tipo;
        this.prioridad = prioridad;
        this.id = id;
        this.indice = indice;
    }

    /** Regla 100 de un permiso por rol. */
    public static ReglaT1 permiso(int recursoId, int codigoRol) {
        return new ReglaT1(TIPO_ROL, 100, recursoId, codigoRol);
    }

    /** Regla común (60, 58, 57, 55, 50, 0); grupo distingue las que comparten prioridad. */
    public static ReglaT1 comun(int prioridad, int grupo) {
        return new ReglaT1(TIPO_COMUN, prioridad, ((long) prioridad << 16) | (grupo & 0xffff), 0);
    }

    public ReglaT1 ip() { ipv4 = true; return this; }
    public ReglaT1 conVlan(int v) { vlan = v; return this; }
    public ReglaT1 desde(String prefijo) { ipv4 = true; ipOrigen = prefijo; return this; }
    public ReglaT1 hacia(String prefijo) { ipv4 = true; ipDestino = prefijo; return this; }
    public ReglaT1 l4(int p, int puerto) { ipv4 = true; proto = p; puertoDestino = puerto; return this; }
    public ReglaT1 conMeter(Meter m) { meter = m; return this; }
    public ReglaT1 alControlador() { controlador = true; return this; }
    public ReglaT1 tabla(int t) { irATabla = t; return this; }

    public boolean descarta() {
        return !controlador && irATabla == null;
    }

    /** Clave única dentro del switch (equivale a la cookie sin el appId). */
    public String clave() {
        return tipo + "/" + Long.toHexString(id) + "/" + indice;
    }

    @Override
    public String toString() {
        StringBuilder m = new StringBuilder();
        if (vlan != null) { m.append("vlan=").append(vlan).append(' '); }
        if (ipv4) { m.append("ip "); }
        if (ipOrigen != null) { m.append("src=").append(ipOrigen).append(' '); }
        if (ipDestino != null) { m.append("dst=").append(ipDestino).append(' '); }
        if (proto != null) {
            m.append(proto == Politica.TCP ? "tcp" : proto == Politica.UDP ? "udp" : "proto=" + proto);
            m.append(puertoDestino != null ? ":" + puertoDestino : "").append(' ');
        }
        StringBuilder a = new StringBuilder();
        if (meter != Meter.NINGUNO) { a.append("meter(").append(meter.name().toLowerCase()).append(") "); }
        if (controlador) { a.append("CONTROLLER "); }
        if (irATabla != null) { a.append("goto:").append(irATabla).append(' '); }
        return String.format("T1 %3d  %s-> %s", prioridad, m.length() == 0 ? "* " : m,
                             descarta() ? "drop" : a.toString().trim());
    }
}
