package pe.edu.pucp.tel354.base.nucleo;

/**
 * Descripción de una regla del régimen base, sin dependencias de ONOS: el adaptador la traduce a
 * FlowRule. Así el conjunto de reglas se prueba y se cuenta sin el controlador (probar_base.sh).
 *
 * Cookie: appId del módulo | tipo (0x01 en M7, 0x05 en M8) | id | índice, con
 * id = prioridad·2^16 + grupo (grupo = puerto en la 800, pabellón en el core, 0 en el resto).
 */
public final class Regla {

    /** Dueño de la regla: M7 (régimen base, cookie 0x01) o M8 (caminos fijos, cookie 0x05). */
    public enum Modulo { M7, M8 }

    /** Valor de {@link #vlan} que significa «cualquier VLAN presente» (OFPVID_PRESENT). */
    public static final int VLAN_PRESENTE = -1;
    /** Valor de {@link #salida} que significa el controlador. */
    public static final int CONTROLADOR = -1;

    public final Modulo modulo;
    public final int tabla;
    public final int prioridad;
    public final long id;
    public final int indice;

    // Coincidencia (null = no se usa)
    public Integer puertoEntrada;
    public Integer ethType;
    public Integer vlan;
    public String ipDestino;

    // Acciones, en este orden: meter, push, set VLAN, pop, set MAC destino, salida o tabla
    /** Puerto cuyo meter ctl_P se aplica (lo resuelve el adaptador). */
    public Integer meterDePuerto;
    public boolean pushVlan;
    public Integer fijarVlan;
    public boolean popVlan;
    public String macDestino;
    public Integer salida;
    public Integer irATabla;

    public Regla(Modulo modulo, int tabla, int prioridad, int grupo, int indice) {
        this.modulo = modulo;
        this.tabla = tabla;
        this.prioridad = prioridad;
        this.id = ((long) prioridad << 16) | (grupo & 0xffff);
        this.indice = indice;
    }

    public Regla enPuerto(int p) { puertoEntrada = p; return this; }
    public Regla eth(int t) { ethType = t; return this; }
    public Regla conVlan(int v) { vlan = v; return this; }
    public Regla haciaIp(String prefijo) { ipDestino = prefijo; return this; }
    public Regla meter(int puerto) { meterDePuerto = puerto; return this; }
    public Regla push(int v) { pushVlan = true; fijarVlan = v; return this; }
    public Regla pop() { popVlan = true; return this; }
    public Regla mac(String m) { macDestino = m; return this; }
    public Regla salir(int p) { salida = p; return this; }
    public Regla tabla(int t) { irATabla = t; return this; }

    /** true si la regla descarta (sin acciones). */
    public boolean descarta() {
        return meterDePuerto == null && !pushVlan && !popVlan && macDestino == null
                && salida == null && irATabla == null;
    }

    /** Clave única dentro del switch (equivale a la cookie sin el appId). */
    public String clave() {
        return modulo + "/" + Long.toHexString(id) + "/" + indice;
    }

    @Override
    public String toString() {
        StringBuilder m = new StringBuilder();
        if (puertoEntrada != null) { m.append("in_port=").append(puertoEntrada).append(' '); }
        if (vlan != null) { m.append("vlan=").append(vlan == VLAN_PRESENTE ? "presente" : vlan).append(' '); }
        if (ethType != null) { m.append(String.format("eth_type=0x%04x ", ethType)); }
        if (ipDestino != null) { m.append("ip_dst=").append(ipDestino).append(' '); }
        StringBuilder a = new StringBuilder();
        if (meterDePuerto != null) { a.append("meter(ctl_").append(meterDePuerto).append(") "); }
        if (pushVlan) { a.append("push_vlan "); }
        if (fijarVlan != null) { a.append("vlan:=").append(fijarVlan).append(' '); }
        if (popVlan) { a.append("pop_vlan "); }
        if (macDestino != null) { a.append("eth_dst:=").append(macDestino).append(' '); }
        if (salida != null) { a.append("output:").append(salida == CONTROLADOR ? "CONTROLLER" : salida).append(' '); }
        if (irATabla != null) { a.append("goto:").append(irATabla).append(' '); }
        return String.format("%s T%d %3d  %s-> %s", modulo, tabla, prioridad,
                             m.length() == 0 ? "* " : m, descarta() ? "drop" : a.toString().trim());
    }
}
