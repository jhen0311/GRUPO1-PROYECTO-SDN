# **Pendientes tras la validación en Mininet**

TEL354 · Grupo 01 · Validación del 12 y 13/10/2026 en la Mininet-VM del grupo. Este documento reúne lo que la validación de la demo dejó pendiente fuera del kit: las correcciones al AVZ07 y al AVZ08, y la especificación de M1 en Java.

> **Estado (13/10/2026).** Las secciones A y B ya están aplicadas en las versiones actualizadas del AVZ07 y el AVZ08, con los cambios resaltados en amarillo para su revisión en Google Docs. Queda pendiente la sección C (M1 en Java). Las tablas de A y B se conservan como registro de qué cambió y por qué.

## **A. Correcciones al AVZ07 (LLSD)**

### **A.1. La VLAN se retira en el acceso, no en el core (cambio de diseño)**

**Evidencia.** Al instalar el régimen proactivo, ONOS dejó en PENDING_ADD las 14 reglas del core con VLAN_POP: las 11 hacia servidores, la de 172.16.0.0/16 y la 190 hacia el Edge. Open vSwitch, en OpenFlow 1.3, rechaza pop_vlan en una regla que no exige VLAN en su match. Confirma el hallazgo 34 de la revisión del AVZ07 («pop_vlan sobre tráfico sin etiqueta»).

**Solución validada.** La VLAN se retira en la Tabla 2 del switch de acceso antes de subir al core, con un match «VLAN presente» (VLAN_VID = OFPVID_PRESENT). El número de entradas no cambia, así que el AVZ08 tampoco.

| **Dónde**                             | **Hoy dice**                                                                                | **Debe decir**                                                                                                                                                                                                                                                             |
|---------------------------------------|---------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Tabla 13, regla 200 (servicios)       | eth_type=0x0800, ipv4_dst=10.200.0.0/16 → output:U                                          | vlan_vid=presente, eth_type=0x0800, ipv4_dst=10.200.0.0/16 → pop_vlan; output:U                                                                                                                                                                                            |
| Tabla 13, regla 180                   | eth_type=0x0800 → output:U (hacia el Edge)                                                  | vlan_vid=presente, eth_type=0x0800 → pop_vlan; output:U (hacia el Edge)                                                                                                                                                                                                    |
| Tabla 14, regla 200 (servidores)      | pop_vlan (si existe); set_field:eth_dst=MAC_srv; output:T                                   | set_field:eth_dst=MAC_srv; output:T                                                                                                                                                                                                                                        |
| Tabla 14, regla 200 (172.16.0.0/16)   | pop_vlan (si existe); set_field:eth_dst=MAC_onos; output:C                                  | set_field:eth_dst=MAC_onos; output:C                                                                                                                                                                                                                                       |
| Tabla 14, regla 190                   | pop_vlan (si existe); output:Edge                                                           | output:Edge                                                                                                                                                                                                                                                                |
| Sección 5.5, párrafo bajo la Tabla 14 | «…pop_vlan sobre una trama sin etiqueta no tiene efecto…»                                   | «El tráfico de los usuarios llega al core sin etiqueta: el switch de acceso retira la VLAN en su Tabla 2 (reglas 200 y 180, con match de VLAN presente), porque OVS no acepta pop_vlan en reglas sin match de VLAN. El core solo agrega la VLAN en el sentido de retorno.» |
| Figura 2 (pipeline)                   | Tabla 2: «200 servidores 10.200.0.0/16 → uplink» y «180 salida por defecto → uplink (Edge)» | Añadir «pop VLAN» a ambas líneas                                                                                                                                                                                                                                           |

*Tabla P.1. Cambios en las Tablas 13 y 14, la sección 5.5 y la Figura 2 del AVZ07.*

### **A.2. Otras precisiones del AVZ07**

| **Dónde**                                        | **Precisión**                                                                                                                                                                                |
|--------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Sección 4.2 / Tabla 10 (sub-opción 11, RFC 5107) | Kea no aplica la sub-opción 11. El Server Identifier se configura en Kea como la dirección del relay (dhcp-server-identifier = 172.16.0.10), con el mismo efecto: las renovaciones van a M1. |
| Sección 3.3 o 9                                  | FreeRADIUS espera 1 s (reject_delay) antes de cada Access-Reject. No afecta a los usuarios válidos y frena la fuerza bruta.                                                                  |
| Sección 2.4 (cookies)                            | ONOS 1.13 permite fijar la cookie con DefaultFlowRule.builder().withCookie(...); la API REST no la expone.                                                                                   |

*Tabla P.2. Otras precisiones del AVZ07.*

### **A.3. Acceso a los servidores de cursos por inscripción (cambio de diseño decidido)**

**Decisión.** Un usuario solo alcanza los servidores de los cursos en los que está inscrito en el ciclo vigente; con el diseño actual, cualquier alumno alcanzaba los servidores de todos los cursos (AVZ07, sección 9, «Acceso por curso»). Los recursos sin curso (Campus Virtual, Biblioteca, bases de datos y accesos de gestión) siguen autorizándose por rol.

**Mecanismo.** Los recursos con curso_id salen de la matriz por rol. Al activar la sesión (cuando ya hay IP), M1 instala en la Tabla 1 una **regla 110** por cada servidor de los cursos del usuario: match en la IP de la sesión, el servidor y el puerto, acción goto_table:2, cookie 0x02 y hard_timeout igual al Session-Timeout. La identidad la garantiza el binding 520, que impide usar la IP de otra sesión. Cuarentena e Invitado nunca reciben reglas 110 (si las tuvieran, la prioridad 110 saltaría el aislamiento de la regla 58).

| **Dónde (AVZ07)**                 | **Cambio**                                                                                                                                                                                                                                      |
|-----------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Tabla 1 (decisiones)              | Nueva decisión D15: «El acceso a los servidores de cursos se concede por inscripción, con una regla 110 por sesión y servidor; los demás recursos siguen por rol.» Consecuencia: costo de c_s entradas por sesión, solo en su switch de acceso. |
| Tabla 6 (uso de las tablas)       | inscripcion y curso pasan a usarse en R2 (acceso por curso). Añadir la vista v_acceso_curso; v_permiso_efectivo excluye los recursos con curso_id.                                                                                              |
| Tabla 5 (cookies)                 | La cookie 0x02 (sesión) incluye también las reglas 110.                                                                                                                                                                                         |
| Tabla 12 (Tabla 1 de acceso)      | Nueva fila: **110** · eth_type=0x0800, ipv4_src=IP/32, ipv4_dst=IP_srv/32, ip_proto, tcp_dst \| udp_dst · goto_table:2 · hard = Session-Timeout · 0x02 · M1. La regla 100 queda solo para recursos sin curso.                                   |
| Tabla 17 (temporizadores)         | Añadir la regla 110 junto a las de la sesión: hard_timeout = Session-Timeout; borrado por cookie 0x02 al cerrarse.                                                                                                                              |
| Sección 6.4 (Fase 3)              | Con el DHCPACK, además del binding 520, M1 instala las reglas 110 de la sesión (consulta v_acceso_curso del usuario).                                                                                                                           |
| Secciones 7.1 y 7.2, Tabla 23     | La matriz por rol no incluye los servidores de cursos. En la Tabla 23, las filas de cursos pasan a «Inscritos en el curso (regla 110 por sesión)» y su regla resultante usa la IP de la sesión.                                                 |
| Sección 7.8 (cambios de política) | Un cambio en inscripcion también dispara policy_changed; M1 recalcula las reglas 110 de las sesiones activas del usuario afectado.                                                                                                              |
| Sección 9                         | Se elimina la limitación «Acceso por curso» y se sustituye por el costo del nuevo mecanismo (ver B.2).                                                                                                                                          |
| Figuras 2 y 5                     | Añadir la regla 110 en la Tabla 1 del pipeline y en la instalación de la sesión.                                                                                                                                                                |

*Tabla P.3. Cambios del AVZ07 por el acceso por curso.*

## **B. Datos medidos para el AVZ08**

| **Dónde (AVZ08)**                              | **Estimado**            | **Medido en la maqueta**                                                                                                 |
|------------------------------------------------|-------------------------|--------------------------------------------------------------------------------------------------------------------------|
| Tabla 9: EAP-TTLS + bind LDAPS + consultas SQL | ≈ 42 ms (35 + 5 + 2)    | **Mediana 11,8–14,0 ms; p95 13,6–17,4 ms**, con **6** rondas RADIUS (no 7)                                               |
| Tabla 9: DHCP relay y lease                    | ≈ 20 ms                 | **Media 2,5 ms; p95 3,6 ms** (Kea + PostgreSQL, sin el tramo host–switch)                                                |
| Sección 3.2: modelo de flows                   | F_acc = c0 + P + 5S + C | **Validado**: as1 42/42 (S = 2, C = 2), as2 35/35 (S = 1, C = 0) y core 31/31, con acceso por curso                      |
| Sección 5.2: precisión de R2                   | 100 % esperado          | **100 %** (80/80) y alcance 100 % (55/55), por usuario; el servidor de un curso sin inscritos queda bloqueado para todos |
| Sección 4.1: tasa de bloqueo                   | 100 % esperado          | **100 %** (3/3) y rol correcto 100 % (5/5)                                                                               |

*Tabla P.4. Valores estimados en el AVZ08 frente a lo medido.*

Aplicado: el AVZ08 incluye la sección 7.1 «Resultados de la maqueta» (Tabla 19), que aclara que la latencia medida aún no incluye el tramo host–switch–ONOS, que se medirá con M1.

### **B.2. Cambios del AVZ08 por el acceso por curso**

El acceso por curso añade c_s entradas por sesión (una por servidor de sus cursos) y retira de las reglas 100 las de los servidores de cursos. Con el inventario del LLSD, las reglas 100 bajan de 27 a 18 (3 cursos × 3 roles académicos), así que c0 = 15 + 18 = 33. Con un equipo por puerto (P = S) y la misma reserva reactiva (R = 516):

<table>
<colgroup>
<col style="width: 100%" />
</colgroup>
<thead>
<tr class="header">
<th><p>S_max = ⌊ (2 000 − 33 − 516) / (6 + c̄) ⌋</p>
<p>c̄ = 1 → 207 sesiones por switch (2 691 en el campus)</p>
<p>c̄ = 2 → 181 sesiones por switch (2 353 en el campus) ← escenario propuesto</p>
<p>c̄ = 3 → 161 sesiones por switch (2 093 en el campus)</p></th>
</tr>
</thead>
<tbody>
</tbody>
</table>

| **Dónde (AVZ08)**                  | **Cambio**                                                                                                                                                                                                                                                                                                                   |
|------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Secciones 2.1 y 2.2 (escenario)    | Nuevo escenario propuesto: c̄ = 2 servidores de curso por sesión y **180 sesiones por switch** (P = 180), 2 340 en el campus (≈ 26 % de las ~9 000 PC). F_acc = 33 + 180 + 7 · 180 = 1 473 (73,7 %) y peor caso 1 989 ≤ 2 000.                                                                                                |
| Sección 3.2 (Tabla 4)              | Nueva fila: regla 110, «por sesión y curso», c_s entradas. c0 = 33.                                                                                                                                                                                                                                                          |
| Sección 3.5 (asintótico)           | F_acc = 15 + Σ\|perm\*\| + P + (5 + c̄)·S_sw + D + 4K ∈ Θ(S_sw). La afirmación central se matiza: las reglas de R2 **por rol** y las del core siguen siendo constantes en el número de usuarios; el acceso por curso añade c̄ entradas por sesión, solo en el switch local, y crece con las inscripciones, no con las cuentas. |
| Secciones 3.7 y 3.8 (Tablas 6 y 7) | Recalcular la sensibilidad con c0 = 33 y 6 + c̄ entradas por equipo. En la Tabla 7, la fila «Restricción por inscripción» pasa a ser el diseño adoptado, con c̄ = 2 (solo cursos con servidor) en lugar de las ≈ 5 reglas supuestas antes.                                                                                     |
| Sección 5.2 (precisión)            | Se elimina la salvedad «un alumno no inscrito no cuenta como no autorizado»: ahora sí cuenta y se bloquea. La batería se hace por usuario (rol + cursos).                                                                                                                                                                    |
| Sección 5.5 (propagación)          | Un cambio de inscripción modifica las reglas 110 de las sesiones activas del usuario: c_s Flow-Mod en su switch.                                                                                                                                                                                                             |
| Sección 6 (hallazgos)              | Nuevo hallazgo H12: el acceso por curso reduce la capacidad de 240 a ~180 sesiones por switch con c̄ = 2.                                                                                                                                                                                                                     |
| Acuerdos de dimensionamiento       | Actualizar el escenario (180 sesiones por switch, 2 340 en el campus) y la justificación asintótica.                                                                                                                                                                                                                         |

*Tabla P.5. Cambios del AVZ08 por el acceso por curso.*

## **C. M1 en Java (siguiente bloque)**

### **C.1. Por qué hace falta**

sesion_manual.py emula el resultado de la admisión, pero no la admisión. Para que un host se autentique de verdad por 802.1X y reciba su IP por DHCP, alguien tiene que recibir los Packet-In de EAPOL (regla 900) y DHCP (regla 525), y eso no se puede hacer por la API REST de ONOS. M1 tiene que ser una app OSGi dentro de ONOS 1.13.

### **C.2. Alcance mínimo para la demo**

| **Fase**                | **Contenido**                                                                                                                                                                                                                                                                                                                                                             | **Prueba de aceptación**                                                                                       |
|-------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------|
| 1\. Autenticador 802.1X | PacketProcessor para EAPOL. EAPOL-Start → EAP-Request/Identity. Cada EAP-Response se encapsula en un Access-Request (EAP-Message, Message-Authenticator, State, NAS-IP 172.16.0.10, NAS-Port, Calling-Station-Id y Called-Station-Id) hacia 10.200.0.10:1812. Access-Challenge → EAP-Request por Packet-Out; Accept → EAP-Success; Reject → EAP-Failure.                  | h1 wpa_supplicant -D wired termina en CTRL-EVENT-EAP-SUCCESS con alumno.demo y en FAILURE con clave incorrecta |
| 2\. Sesión y admisión   | Con el Accept: lee Filter-Id, Session-Timeout y el VSA PUCP; resuelve pabellón y VLAN; aplica max_sesiones; hace el upsert de dispositivo y el INSERT … RETURNING id de sesion; instala 525 y 505 con cookie 0x02 y hard_timeout.                                                                                                                                         | Las reglas 525 y 505 aparecen con la cookie y el timeout correctos                                             |
| 3\. Relay DHCP          | Recibe DHCP por la regla 525, agrega la opción 82 (sub 1 = N, sub 2 = k) y giaddr 172.16.0.10, y reenvía por UDP desde 172.16.0.10:67 hacia 10.200.0.13:67. Las respuestas vuelven al host por Packet-Out. Con el ACK instala 520, las reglas 110 de acceso por curso (de v_acceso_curso) y las reglas 200 y 190 de la Tabla 2, y actualiza sesion (ip, vlan_id, ACTIVA). | h1 dhclient obtiene 10.17.x.y; contar_flows.py y bateria_r2.py --usuario dan lo mismo que con sesion_manual.py |
| 4\. Fin de sesión       | FLOW_REMOVED de 525 o 505 → cierre (EXPIRACION) y rebote del puerto (DeviceAdminService.changePortState); EAPOL-Logoff → DESCONEXION; borrado por cookie.                                                                                                                                                                                                                 | La sesión pasa a CERRADA en la BD y sus reglas desaparecen                                                     |
| 5\. Mínimos de M8 y M2  | Proxy ARP del gateway virtual (MAC 02:00:00:00:00:‹b›) por la regla 800; DROP 150 en el slow-path de la regla 55.                                                                                                                                                                                                                                                         | Funciona sin ARP estático en los hosts; aparecen DROP 150 en contar_flows.py                                   |

*Tabla P.6. Fases de M1 para la demo.*

> Sin la fase 5, un host con IP por DHCP necesita ARP estático al gateway de su rol (arp -s 10.‹b›.0.1 02:00:00:00:00:‹b›), igual que en el modo --manual.

### **C.3. Piezas técnicas de ONOS 1.13**

| **Necesidad**         | **API o recurso**                                                                                                                               |
|-----------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| Esqueleto de la app   | Arquetipo Maven org.onosproject:onos-bundle-archetype:1.13.0; se instala con onos-app localhost install! target/\*.oar                          |
| Recepción de paquetes | PacketService.addProcessor y requestPackets (o confiar en las reglas 900 y 525 del régimen base)                                                |
| Clases de protocolo   | org.onlab.packet.EAPOL, EAP, RADIUS, RADIUSAttribute, DHCP y DhcpOption (onlab-misc)                                                            |
| Referencia de código  | La app AAA de OpenCORD (opencord/aaa), escrita para ONOS 1.x, implementa el relay EAPOL ↔ RADIUS                                                |
| Reglas                | FlowRuleService con DefaultFlowRule.builder().withCookie(...).withHardTimeout(...); borrado por cookie con getFlowEntriesById y removeFlowRules |
| Rebote del puerto     | DeviceAdminService.changePortState(deviceId, port, false/true)                                                                                  |
| Base de datos         | Driver JDBC de PostgreSQL embebido en el bundle (Embed-Dependency del maven-bundle-plugin), con el usuario onos_m1                              |
| Estado                | ConsistentMap de sesiones indexado por MAC, IP y usuario                                                                                        |
| Configuración         | Network config (appId tel354.m1): secreto RADIUS, IP del relay, IP de FreeRADIUS y de Kea                                                       |
| Socket UDP 67         | ONOS corre como root en la VM (shell de root), así que puede enlazar 172.16.0.10:67                                                             |

*Tabla P.7. APIs y recursos de ONOS 1.13 para M1.*

### **C.4. Métrica que desbloquea**

Con M1, la latencia total de la Tabla 9 del AVZ08 (del EAPOL-Start a SessionReady) se mide con marcas de tiempo en el log de M1. Además, la demo pasa del modo --manual al flujo real: la topología se levanta sin --manual y luego wpa_supplicant y dhclient en cada host.

## **D. Correcciones ya aplicadas al kit y al manual**

Quedan aquí como registro; no requieren acción.

1\. eapoltest no existe en Ubuntu 20.04: eapol_test se compila desde wpa_supplicant 2.9 (tools/instalar_dependencias.sh).

2\. La compilación de eapol_test falla por D-Bus: se desactiva CONFIG_CTRL_IFACE_DBUS\*.

3\. El postinst de FreeRADIUS intenta arrancar el servicio y falla: la instalación usa policy-rc.d.

4\. PostgreSQL crea el clúster en LATIN1: la base campus se crea en UTF-8 con TEMPLATE template0.

5\. El hook lease_cmds requiere isc-kea-hooks.

6\. AppArmor impide a slapd leer /etc/tel354/certs (error 80): setup_ldap.sh añade la regla.

7\. El locale C debe ir entre comillas dobles en CREATE DATABASE.

8\. pg_ctlcluster delega en systemd (namespace raíz): se usa --skip-systemctl-redirect dentro de svc.

9\. Kea 2.4.2 exige el socket de control en /var/run/kea.

10\. La salida de recursos.py quedaba en el búfer: se usa python3 -u.

11\. OVS rechaza pop_vlan sin match de VLAN: la VLAN se retira en el acceso (ver A.1).

12\. ONOS 1.13 exige burstSize en las bandas de los meters en kbps.

13\. La terminal en latin-1 rompe las tildes: PuTTY en UTF-8 y export PYTHONIOENCODING=utf-8 LANG=C.UTF-8.

14\. En la CLI de Mininet, \# inicia un comentario: las ediciones de archivos se hacen desde la tercera ventana.

15\. Acceso por curso (A.3): vistas v_permiso_efectivo (sin cursos) y v_acceso_curso; campus_rol_efectivo con SECURITY DEFINER; trigger en inscripcion; servidor Nube TEL141 (10.200.2.20) e inscripciones de la demo; reglas 110 en sesion_manual.py; contar_flows.py con el término C; export_matriz.py y bateria_r2.py --usuario; manual y README con los nuevos valores esperados (core 29 + 2, acceso 28 + 2, as1 42, as2 35, core 31; precisión 80/80).
