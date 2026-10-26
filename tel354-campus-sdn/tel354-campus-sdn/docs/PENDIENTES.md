# **Pendientes tras la validación en Mininet**

TEL354 · Grupo 01 · Validación del 12 y 13/10/2026 en la Mininet-VM del grupo. Este documento reúne lo que la validación de la demo dejó pendiente fuera del kit: las correcciones al AVZ07 y al AVZ08, y la especificación de M1 en Java.

> **Estado consolidado (19/10/2026, noche).** Este archivo es un registro acumulado: las secciones A a H se conservan como historial. **A, B, C, D y F.1–F.3 están resueltos; E está resuelto salvo la app M2; G y H están aplicados en los documentos.** No quedan pendientes documentales entre AVZ07, AVZ08, Informe Parcial y LLSD de R3. Lo único abierto es código y la demo de R3:
>
> | # | Pendiente abierto | Origen | Requisito |
> |---|---|---|---|
> | 1 | P1–P7 del LLSD de R3: proxy ARP solo al gateway y ArpProbe, BindingViolation (IP y ARP), app M2, eventos de sesión en tel354-api, `revocar` y paso forzado, app M6 | F.4 #1–#6 y #9 | R3 |
> | 2 | P8: EAP-Request/Identity tras el rebote y acción de `wpa_cli` que reinicia dhclient | G | R1 (necesario para N3 de R3) |
> | 3 | Meter `ctl_P` en las reglas 525 y 505 (hoy solo la 800 lo lleva) | F.4 #7 | R1; el diseño ya lo declara |
> | 4 | Cierre por enlace caído (PORT_UPDATED → DESCONEXION) y por 60 s sin DHCP en AUTENTICADA | F.2 #16, F.4 #8 | R1; el diseño ya lo declara |
> | 5 | ~~Sondeo de `login.sh` de 0,5 s a 0,1 s~~ **Aplicado el 23/10** (falta repetir la medición de latencia) | G | Medición |
> | 7 | ~~Al superar max_sesiones, M1 cierra la sesión más antigua **y rebota su puerto** (`cerrar(sv, LIMITE_SESIONES, true)`); el AVZ07 (Tabla 20) dice que no se rebota, para evitar que el equipo desplazado se reautentique y desplace a otro. Cambiar a `false`~~ **Aplicado en el código el 23/10** (`cerrar(sv, LIMITE_SESIONES, false)`; falta recompilar M1 y verificar) | Revisión del 20/10 | R1 |
> | 8 | **Propuesta (por confirmar):** reemplazar `onos_static.py` por apps (M2 con la Tabla 1 proactiva y `policy_changed`; M7/M8 con el régimen base al conectarse cada switch) | Revisión del 26/10 | R2 (transversal) |
> | 6 | Apps M3, M4a y M5 y casos E1–E14; sus resultados van a la Tabla 25 y la sección 10.3 del LLSD de R3 | LLSD R3, Tabla 27 | R3 |
>
> Plan detallado de los módulos que faltan, con sus acuerdos y orden: [`PLAN_MODULOS.md`](PLAN_MODULOS.md). Manuales: `MANUAL_GENERAL.md` (despliegue y módulos de R1 a R5) y `MANUAL_PRUEBAS_R1.md`, `MANUAL_PRUEBAS_R2.md` (pruebas).
>
> Aceptado sin cambio: las reglas del régimen base (cookies 0x01, 0x03–0x05) se instalan por REST con el appId de REST en la maqueta (F.4 #10).
>
> **Estado (19/10/2026).** M1 validado en la Mininet-VM en modo automático (login de alumno.demo, batería 30/30 y 15/15). Corregidos: zona horaria de Kea/PostgreSQL (UTC), `Import-Package` de checkerframework, detección de interfaz bajo `nsenter`. El manual v2 está en `docs/MANUAL_DESPLIEGUE.md`; su sección 14 reúne los pendientes de R1–R5.
>
> **Estado (18/10/2026).** La sección C está implementada: M1 es una app de ONOS 1.13 en `r1-identidad/onos-app-m1/` y se validó con el banco de pruebas (ver `r1-identidad/README.md`). Cambio respecto de C.2: se evaluó la app nativa AAA de OpenCORD como autenticador y se descartó (no soporta EAP-TTLS en la rama de ONOS 1.13, su evento no trae MAC ni usuario y depende de SADIS); M1 es un autenticador de paso. Quedan pendientes la sección E y la validación de M1 en la Mininet-VM.
>
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


## **E. Pendientes tras implementar M1 (18/10/2026)**

| **Dónde** | **Pendiente** |
|---|---|
| Mininet-VM | Compilar e instalar M1 (`instalar_m1.sh`) y repetir las métricas de la Tabla M.8 en modo automático (sin `--manual`); medir la latencia de EAPOL-Start a SessionReady con las marcas de tiempo del log de M1 |
| AVZ07, sección 3 | Registrar la decisión «M1 es autenticador 802.1X de paso; AAA de OpenCORD evaluada y descartada», con la evidencia de `r1-identidad/README.md` |
| AVZ07, sección 3.1 | Tabla de confidencialidad por tramo; el supplicant verifica el nombre del servidor (`domain_suffix_match`); propuesta de RadSec (RFC 6614) para producción |
| AVZ07, sección 3.2 | PostgreSQL con TLS (`hostssl` para onos_m1, onos_m2 y onos_m6; certificado `db.pem` de la CA de la demo) |
| AVZ07, sección 4 | Kea con `TZ=UTC` (defecto de Kea 2.4 con leases en PostgreSQL al renovar o liberar fuera de UTC) |
| Manual de despliegue | Flujo automático: sin `--manual`, `instalar_m1.sh` después de `onos_static.py` y `entrar.sh hX` para iniciar sesión; `--manual` y `sesion_manual.py` pasan a diagnóstico; rutas de la estructura nueva del repositorio |
| R1 | Credencial temporal de invitado |
| M2 | App de ONOS: DROP 150 y `policy_changed` |


## **F. Cotejo AVZ07 v3, AVZ08 v2 e Informe Parcial frente a la implementación (19/10/2026)**

> **Estado (19/10/2026, más tarde).** Aplicados con resaltado amarillo en las versiones «cotejo19oct» del AVZ07 (F.2 #1–#15, salvo #16, que es de código) y del AVZ08 (F.3 #1–#4). Quedan sin cambiar: la Figura 2 del AVZ07 (imagen del pipeline: aún muestra arp_P y no muestra 910 ni 120) y la Tabla 4 del Informe Parcial (formato de cookie). F.4 se resuelve con el LLSD de R3 (Tabla 2) y F.5 quedó incorporado en ese LLSD.

Fuentes cotejadas: `AVZ07-TEL354_2026-2_G1` (LLSD de R1 y R2), `AVZ08-TEL354_2026-2_G1` (análisis cuantitativo), `G1_Informe_Parcial` (base de R3–R5) y el código validado en la Mininet-VM (M1 en modo automático, 19/10).

### F.1. Estado de los pendientes anteriores

| Pendiente | Estado en los documentos |
|---|---|
| A.1 VLAN retirada en el acceso (Tablas 13 y 14, sección 5.5, Figura 2) | **Aplicado** en AVZ07 y en el Informe Parcial (sección 1.4) |
| A.2 Sub-opción 11, `reject_delay`, `withCookie` | **Aplicado** (AVZ07 4.2, 9 y 2.4) |
| A.3 Acceso por curso (D15, regla 110, Tablas 12, 17 y 23, sección 7.8) | **Aplicado** |
| B Datos medidos y cambios por el acceso por curso (AVZ08 Tablas 4, 6, 7, 17 y 19; secciones 3.5 y 5.2) | **Aplicado** |
| E AVZ07 sección 3: decisión «M1 autenticador de paso; AAA evaluada y descartada» | **Pendiente** (no aparece en la Tabla 1 ni en el texto) |
| E AVZ07 sección 3.1: confidencialidad por tramo, `domain_suffix_match`, RadSec | **Pendiente** (3.1 solo describe host → FreeRADIUS → LDAP; no cubre M1 ↔ FreeRADIUS ni M1 ↔ PostgreSQL) |
| E AVZ07 sección 3.2: PostgreSQL con TLS | **Pendiente** |
| E AVZ07 sección 4: Kea con `TZ=UTC` | **Pendiente** |
| E Métricas en modo automático y latencia de extremo a extremo | **Pendiente** (AVZ08 7.1 aún dice que M1 no está implementado) |

### F.2. Cambios pendientes en el AVZ07

| # | Dónde | Hoy dice | Debe decir | Motivo |
|---|---|---|---|---|
| 1 | Tabla 5 y sección 2.4 (también Tabla 4 del Informe Parcial) | Bits 63–56 tipo, 55–48 código de rol, 47–0 identificador | Bits 63–48 **appId** de la app de ONOS, 47–40 tipo (0x01…0x07), 39–8 identificador (sesion.id, recurso.id o incidente.id), 7–0 índice de la regla dentro del grupo | ONOS 1.13 deriva el appId de los 16 bits altos de la cookie (`DefaultFlowRule`: `appId = flowId >>> 48`). Con el formato actual, ONOS atribuiría la regla a una app inexistente y `getFlowEntriesById(appId)` no la encontraría. M1 ya usa el formato corregido |
| 2 | Tabla 1 | D1 a D15 | Añadir **D16**: «M1 es autenticador 802.1X de paso (RFC 3579); la app AAA de OpenCORD se evaluó y se descartó: su rama para ONOS 1.13 no reenvía EAP-TTLS, su evento no trae MAC ni usuario y depende de SADIS» | Pendiente E |
| 3 | Sección 3.1 (o nueva 3.5) | Tres tramos: host → FreeRADIUS, FreeRADIUS → LDAP, verificación | Tabla de confidencialidad por tramo: host ↔ M1 (EAP-TTLS de extremo a extremo; en claro solo `anonymous`), M1 ↔ FreeRADIUS (RADIUS con Response Authenticator y Message-Authenticator verificados; RadSec para producción), FreeRADIUS ↔ LDAP (LDAPS), M1/M2/M6 ↔ PostgreSQL (TLS, `hostssl`, `verify-ca`) | Pendiente E; requisito de credenciales cifradas |
| 4 | Sección 6.2 (y sección 2.1.4 del Informe Parcial) | `identity="a20210845"`, `password="****"` en el archivo, `ca_cert="/etc/ssl/campus-ca.pem"` | `anonymous_identity="anonymous"`, `domain_suffix_match="radius.campus.pucp.edu.pe"`, `ca_cert` de la CA del campus; la identidad y la contraseña se entregan por el socket de control (`wpa_cli`), nunca en disco | Configuración validada (`login.sh`) |
| 5 | Sección 4.3, Tabla 10 (fila «Backend de leases») | PostgreSQL | PostgreSQL con zona horaria UTC (`timezone='UTC'`) y Kea arrancado con `TZ=UTC` | Kea 2.4 con leases en PostgreSQL falla al renovar o liberar fuera de UTC: DHCPNAK y pérdida de la IP a la hora |
| 6 | Sección 3.2, párrafo «Escritura» | Sin cifrado | M1, M2 y M6 conectan con TLS (`hostssl` en `pg_hba.conf`, certificado `db.pem` de la CA, `sslmode=verify-ca`) | Pendiente E |
| 7 | Sección 4.1, párrafo bajo la Tabla 8 | «cada switch de acceso admite como máximo 240 sesiones simultáneas» | «… 180 sesiones simultáneas (AVZ08, sección 3.7)» | Quedó del diseño anterior al acceso por curso; contradice D15 y la sección 9 |
| 8 | Tabla 11 (Tabla 0) | Fila 60 «R3-d (actor sin admisión completa)» | Eliminar la fila | D11 eliminó MAB; el Informe Parcial (Tabla 5) ya no la incluye |
| 9 | Tabla 11 y Tabla 15 | Meter `arp_P` solo en la regla 800 | Meter `ctl_P` por puerto en las reglas **800, 525 y 505** | Cambio del Informe Parcial (Tabla 9): un host autenticado no puede acaparar el techo de Packet-In con DHCP o IP falsa |
| 10 | Tabla 11, Tabla 12 y Tabla 5 | Sin reglas de R4 | Reglas reservadas **910** (Tabla 0, EAPOL por puerto, drop) y **120** (Tabla 1, meter r4) con cookie **0x07**; reserva de mitigación de 96 entradas compartida por R3 y R4 | Informe Parcial, Tablas 4–6 |
| 11 | Tabla 14 (core) | Regla 200 de servidores: `output:T` | `output:T; output:IDS` (copia hacia el IDS) | Informe Parcial, Tabla 8: base del complemento IDS de R3 y R5 |
| 12 | Sección 5.5 y sección 9 («NAT en el Edge») | El Edge hace NAT (supuesto) | La NAT la hace el proveedor después del edge; el edge no traduce direcciones y por eso ve las IP internas | Informe Parcial, sección 2.5.1 |
| 13 | Sección 6.1 | `cfg set … HostLocationProvider requestArp false` | `cfg set … HostLocationProvider requestInterceptsEnabled false` | Es la propiedad validada en la maqueta (retira la regla 40000 de ARP) |
| 14 | Sección 6.3 | «M1 solicita a M2 la resolución (rol, pabellón)» | M1 resuelve pabellón y VLAN consultando `switch_acceso` y `pabellon` | Así quedó implementado; evita una dependencia de M1 hacia M2 en la admisión |
| 15 | Sección 6.5 | Almacén en ConsistentMap | Mantener como diseño; añadir: «en la maqueta, una instancia, mapa en memoria; al reiniciar, M1 cierra las sesiones huérfanas y rebota sus puertos» | Implementación real |
| 16 | Tabla 19 y Tabla 21 | AUTENTICADA: 60 s sin DHCP → CERRADA; PORT_STATUS de enlace caído → DESCONEXION | Sin cambio en el diseño; **pendiente en el código** (ver F.4) | Hoy la sesión AUTENTICADA solo cierra por hard_timeout |

### F.3. Cambios pendientes en el AVZ08

| # | Dónde | Hoy dice | Debe decir |
|---|---|---|---|
| 1 | Sección 7.1, primer párrafo y Tabla 19 | «… las de sesión se emularon, mientras M1 no está implementado» | Añadir los resultados en modo automático (19/10): login real por 802.1X y DHCP con M1, batería de alumno.demo con precisión 30/30 y alcance 15/15; flows con sesiones reales y latencia EAPOL-Start → SessionReady (`latencia_m1.py`) por medir |
| 2 | Tabla 19 | Sin la fila de Kea | Nueva observación: «Kea 2.4 con leases en PostgreSQL exige UTC; fuera de UTC la renovación falla con DHCPNAK» |
| 3 | Tabla 9 (y Tabla 43 del Informe Parcial) | 7 rondas host–M1–FreeRADIUS | Mantener 7 como cota conservadora y anotar que la maqueta midió 6 rondas |
| 4 | Tabla 3, sección 3.4 y Tabla 15 del AVZ07 | Meter `arp_P` | `ctl_P` (800, 525 y 505), 183 permanentes más hasta 73 de R4 (256 por switch), como en el Informe Parcial |
| 5 | Tabla 16 | ConsistentMap ≈ 2,3 MB | Sin cambio; la nota de la maqueta va en el AVZ07 (F.2 #15) |

### F.4. Brechas entre el diseño y el código (afectan a R3)

| # | Diseño | Código hoy | Acción |
|---|---|---|---|
| 1 | M8 responde solo el ARP del gateway virtual; «M8 no responde ARP entre usuarios» (AVZ07 7.4; Informe 2.3.2) | `ProxyArp` responde con la MAC del gateway **cualquier** dirección buscada dentro de la sesión | Responder solo si la IP buscada es 10.‹b›.0.1; si no, no responder y emitir el evento de ARP no-gateway que alimenta D(s, W). **Bloqueante para R3** (un `nmap -sn` vería a todos los hosts «vivos») |
| 2 | Regla 505 → BindingViolation (M1) | El Packet-In IPv4 que no es DHCP se ignora | Publicar BindingViolation con la sesión, la IP usada y la concedida. **Bloqueante para R3** |
| 3 | ARP con par (IP, MAC) incoherente → BindingViolation (M8) | Se descarta en silencio (log debug) | Publicar BindingViolation (suplantación ARP) |
| 4 | Eventos del bus de ONOS (SessionReady, SessionClosed, AuthFailed…) | Solo log y tabla `sesion` | Servicio y eventos de M1 en un bundle de API común (ver propuesta de R3) |
| 5 | M2: slow-path de la regla 55, DROP 150 de 8 s, AuthorizationDenied | No existe (la regla 55 sube al controlador y nadie la atiende) | App M2. **Bloqueante para R3**: es la fuente de P, D, N y F |
| 6 | `revocar(sesión, motivo)`, paso forzado a cuarentena con `lease4-del` | No existe | Operaciones de M1 para M5 |
| 7 | Meter `ctl_P` en 525 y 505 | Reglas sin meter | M1 necesita el id del meter de cada puerto (convención o servicio de M7) |
| 8 | Cierre por enlace caído y por 60 s sin DHCP | No existe | DeviceListener (PORT_UPDATED) y temporizador en M1 |
| 9 | M6: `auth_log` | No existe | App M6 mínima |
| 10 | Cookies del régimen base (0x01, 0x03, 0x04, 0x05) | `onos_static.py` instala por REST con el appId de REST | Aceptado en la maqueta; queda para M2/M7/M8 como apps |

### F.5. Observaciones del Informe Parcial que debe resolver el LLSD de R3

| # | Dónde | Observación |
|---|---|---|
| 1 | Tabla 23 | Las Tablas 24, 28, 51 y 53 usan la métrica **P(s, d, W)** (puertos distintos de un mismo destino), pero la Tabla 23 no la define |
| 2 | Sección 2.3.2 y Tabla 11 | M4a figura como suscriptor de PacketObserved, pero 2.3.2 dice que R3 no lo usa (para no contar dos veces); precisar que M4a lo usa solo para R5 |
| 3 | Sección 2.3.2 | El conteo de D por ARP exige el evento de F.4 #1 y la regla 800 con `ctl_P` (10 pps): fija t_det ≥ 0,8 s (Tabla 51) |
| 4 | Sección 2.3.3 | La renovación de N2 lee el contador de la regla 301 cada 5 s: es la única dependencia de R3 respecto de M3 (estadísticas) |
| 5 | Tablas 26–27 | Falta el formato de la cookie 0x06 con índice (4 reglas por sesión en N2) y la regla de qué hacer si el usuario tiene sesiones en dos switches (reserva de cada switch) |

## G. Métricas de R1 y R2 en modo automático (19/10/2026) y su registro en los documentos

Fuente: `evidencias.tgz` (instantáneas `20261019-1801`, `-1807`, `-1809`, `-1847`, `-1854`, baterías por usuario, rechazos e invitado).

| Métrica | Resultado | Registrado en |
|---|---|---|
| Latencia EAPOL-Start → AUTENTICADA (22 logins) | mediana 59,5 ms, p90 113 ms, máx. 118 ms (atípico 414 ms) | AVZ08 4.2, 7.1, Tabla 19, conclusiones; Informe 3.1.2, Tabla 59 |
| Latencia EAPOL-Start → SessionReady | bimodal: 102–164 ms (mediana 122,5 ms, 10 logins) y 606–698 ms (12); media 400 ms. La segunda moda es el sondeo de 0,5 s de `login.sh` | ídem; LLSD R3 Tabla 22 (N3 ≈ 5,1 s con 120 ms medidos) |
| Flow entries con sesiones reales | as1 42/42 y 36/36; as2 35/35 y 30/30; core 31/31; reglas de M1 = 5S + C | AVZ08 Tabla 19; Informe 3.2.1 y Tabla 59 |
| Confidencialidad | 12 paquetes RADIUS (6 rondas), contraseña 0 veces, `anonymous` en los Access-Request, identidad interna solo en el Access-Accept, Message-Authenticator en los 12 | AVZ07 3.1; AVZ08 Tabla 19; Informe 2.1.4 |
| Tasa de bloqueo con M1 | 5/5 Access-Reject con el puerto cerrado | AVZ07 Tabla 21; AVZ08 4.1 y Tabla 19; Informe 3.1.3 |
| Precisión y alcance por rol | 125/125 bloqueados y 55/55 conectados (alumno, docente, TI, invitado) | AVZ08 5.2 y Tabla 19; Informe 3.2.5 |
| Invitado temporal | Session-Timeout 3 419 s = vigencia restante; 5 reglas, sin 110; revocado → Access-Reject | AVZ07 3.3, Tabla 6, Tabla 24; AVZ08 Tabla 19; Informe 2.2.5 y Tabla 21 |
| Expiración | cierre a los 120,3 s, reglas retiradas, puerto rebotado 1,5 s | AVZ07 Tabla 21; AVZ08 Tabla 19 |
| Rebote | el host no reinició 802.1X ni DHCP (fallo seguro) | AVZ07 7.7 y 9; AVZ08 4.6; Informe 3.3.2; **LLSD R3 P8** |

Otras correcciones aplicadas en la misma pasada: Tabla 4 del Informe (cookie con appId), Tabla 11 (PacketObserved en M4a solo para R5), Tabla 23 (métrica P), memoria de M4a (≤ 25 MB, Tablas 50 y 59 y sección 3.3.4), configuración de wpa_supplicant (2.1.4 y Tabla 17); AVZ07 Tabla 5 (DROP 150 con appId de M2, 0x07 con appId de M5).

**Nuevos pendientes de código**
- **P8 (M1):** al rehabilitar el puerto tras un rebote, enviar EAP-Request/Identity por Packet-Out (reintentos cada 30 s, hasta 3); en `login.sh`, acción de `wpa_cli -a` que reinicie dhclient al completar 802.1X. Verificar en E6/E12.
- `login.sh`: bajar el sondeo de `wpa_cli status` de 0,5 s a 0,1 s, para que la latencia medida no dependa del script.
- `crear_invitado.py --revocar` no cierra la sesión abierta: usar `revocar` de M1 cuando exista (P6).
- `expiracion_log.txt` quedó vacío (el comando manual invocó `su` sin argumentos); el dato de expiración se tomó de la tabla `sesion` (sesión 23) y del log de M1.
- Figura 2 del AVZ07 redibujada (ctl_P en 800, 525 y 505; reglas 910 y 120).

## H. Cotejo cruzado final de AVZ07, AVZ08, Informe Parcial y LLSD de R3 (19/10/2026)

Corregido con resaltado:
- Informe 1.3: `requestArp` → `requestInterceptsEnabled` (ya estaba en el AVZ07).
- AVZ08 Tabla 9 e Informe Tabla 43: «Resolución en M2» → «Resolución de pabellón y VLAN en M1» (así quedó implementado).
- AVZ08 Tabla 19: la fila de latencia de servicios ya no dice «se medirá con M1».
- AVZ07 6.5: nota de la maqueta (mapa en memoria; sesiones huérfanas cerradas y puertos rebotados al reiniciar M1).
- AVZ07 7.7, Informe 2.3.3 y Tabla 29: paso forzado con EAP-Request/Identity (P8).
- AVZ07 9: limitación «Vista de hosts de ONOS» (dos entradas por equipo: MAC sin VLAN y MAC con VLAN).
- Informe Tabla 11: eventos ArpProbe y CounterDelta del LLSD de R3.
- LLSD de R3: espacio en «5 s y», referencias a la cookie y a la memoria de M4a ya corregidas en los otros documentos, nota sobre la diferencia de 1 ms con la Tabla 52 del Informe.

Sin pendientes documentales entre los cuatro documentos. Lo que queda es código (P1–P8, sondeo de `login.sh`) y la demo de R3 (E1–E14), cuyos resultados entrarán a la Tabla 25 y la sección 10.3 del LLSD de R3.
