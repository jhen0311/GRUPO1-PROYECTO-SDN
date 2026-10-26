# Manual de pruebas de R1: identidad y sesiones

**TEL354 · PUCP 2026-2 · Grupo 01 · Versión 1 · 26/10/2026**

R1 exige controlar el acceso a la red de los usuarios válidos según su rol. En la solución, un equipo no tiene red hasta que su usuario se autentica por 802.1X; entonces M1 abre la sesión con el rol que resuelve PostgreSQL, le entrega una IP del pool de su rol y su pabellón, e instala las reglas de la sesión.

Este manual verifica ese comportamiento y obtiene las métricas que usan el AVZ08 y el Informe Parcial. Los resultados de referencia son los de la validación del 19/10/2026.

**Cómo leer los comandos.** Antes de cada bloque se indica dónde ejecutarlo. El bloque contiene solo comandos, sin `sudo` (en la VM se trabaja como root).

---

## 0. Requisitos previos

Seguir el manual general (`MANUAL_GENERAL.md`):

| Paso | Sección del manual general |
|---|---|
| VM, repositorio, dependencias, ONOS y servicios instalados | 3 a 5 |
| ONOS en marcha y topología levantada con `--services` | 6.2 y 6.3 |
| Régimen proactivo instalado (`onos_static.py`) | 7 |
| M1 instalado y activo | 9.1 (la sección 1 de este manual se hace **antes**) |

| Ventana | Prompt |
|---|---|
| ONOS | `onos>` |
| Mininet | `mininet>` |
| Raíz | `root@mininet-vm:/home/mininet/tel354-campus-sdn#` |
| Host | `[h1] /home/mininet/tel354-campus-sdn#` (abierta con `entrar.sh`) |

| Métrica | Sección |
|---|---|
| Tasa de bloqueo de accesos inválidos | 1 y 5 |
| Rol efectivo correcto | 1 y 3 |
| Latencia de admisión | 1 y 9 |
| Confidencialidad de las credenciales | 4 |
| Ciclo de vida de la sesión (cierre, expiración, límite) | 7 y 8 |

*Tabla R1.0. Métricas de R1 y dónde se obtienen.*

---

## 1. Servicios de identidad sin M1

Se hace **antes** de instalar M1 (o con M1 desactivado), porque `dhcp_relay_test.py` usa la misma dirección 172.16.0.10:67 que el relay de M1. Si M1 ya está activo, en `onos>` ejecutar `app deactivate tel354.m1` y volver a activarlo al terminar.

En `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
python3 r1-identidad/tests/r1_radius.py --bateria
python3 r1-identidad/tests/r1_radius.py --latencia 30
python3 r1-identidad/tests/dhcp_relay_test.py --casos 1:1,3:1,5:2 --repeticiones 20
python3 r1-identidad/tests/dhcp_relay_test.py --rol-invalido
```

| Prueba | Qué verifica | Referencia (19/10) |
|---|---|---|
| `--bateria` | FreeRADIUS + LDAP + PostgreSQL con `eapol_test`: rechazos y rol efectivo | Bloqueo 3/3 (clave incorrecta, inexistente, inactivo); rol correcto 5/5 (jp → JP, sancion → CUARENTENA). Cada rechazo tarda ~1 s (`reject_delay`) |
| `--latencia 30` | Tramo de servicios de la autenticación | 6 rondas RADIUS; mediana 11,8–14,0 ms; p95 13,6–17,4 ms |
| `dhcp_relay_test.py` | Kea con opción 82: pool por rol y pabellón | 100 % en el pool 10.(16k+N).0.0; gateway 10.‹16k›.0.1; server-id 172.16.0.10; media 2,5 ms, p95 3,6 ms |
| `--rol-invalido` | Kea no entrega IP a una clase inexistente | «SIN OFERTA» |

---

## 2. ANTES del login: h1 no tiene red

Se hace con `onos_static.py` ya instalado. Abrir la shell de h1 sin login: en `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
bash r1-identidad/host/entrar.sh h1 --shell
```

En `[h1] /home/mininet/tel354-campus-sdn#` ejecutar:
```bash
ip -br addr
ip route
ping -c2 10.200.1.10
```

Esperado: h1-eth0 UP sin IPv4, tabla de rutas vacía y «connect: Network is unreachable».

En `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
ovs-ofctl -O OpenFlow13 --names --rsort=priority dump-flows as1 | grep -E "as1-eth2|00:00:00:10:01:01"
```

Esperado: solo la regla **800** (ARP de `in_port=as1-eth2` → CONTROLLER, con meter). Ninguna regla con la MAC de h1.

**Opcional: una IP puesta a mano tampoco sirve.** En `[h1] /home/mininet/tel354-campus-sdn#` ejecutar:
```bash
ip addr add 10.17.0.99/12 dev h1-eth0 && ip route add default via 10.16.0.1
arp -s 10.16.0.1 02:00:00:00:00:10
ping -c3 10.200.1.10
```

Esperado: 100 % de pérdida, y en la ventana raíz el contador `n_packets` de la regla **10** (descarte de la Tabla 0) sube. Antes de seguir, limpiar h1: en `[h1] /home/mininet/tel354-campus-sdn#` ejecutar:
```bash
ip -4 addr flush dev h1-eth0; ip neigh flush dev h1-eth0
exit
```

---

## 3. Login y sesión

### 3.1. Login de h1

Con M1 activo, en una ventana nueva, en `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
bash r1-identidad/host/entrar.sh h1
```

Ingresar `alumno.demo` y `Alumno2026!`. Salida esperada:
```
=== Red del campus · inicio de sesión (h1-eth0) ===
Usuario: alumno.demo
Contraseña:
Autenticando (802.1X / EAP-TTLS).
Autenticación correcta: alumno.demo
Solicitando dirección IP (DHCP)... listo

h1-eth0   UP   10.17.0.x/12
default via 10.16.0.1 dev h1-eth0
Sesión abierta. Para cerrarla: bash …/logout.sh
[h1] /home/mininet/tel354-campus-sdn#
```

| # | Evento | Quién | Reglas |
|---|---|---|---|
| 1 | `wpa_supplicant` envía EAPOL-Start; la contraseña entra por el socket de control (no aparece en `ps` ni en disco) | Host | 900 |
| 2 | M1 encapsula cada EAP en un Access-Request (6 rondas) hacia FreeRADIUS; el túnel TTLS verifica la CA y el nombre del servidor | M1 ↔ FreeRADIUS ↔ LDAPS | — |
| 3 | Access-Accept con rol y Session-Timeout. M1 registra `dispositivo` y `sesion` AUTENTICADA | M1 → PostgreSQL (TLS) | 525, 505 |
| 4 | dhclient pide IP; M1 añade la opción 82 (N, k) y relaya a Kea | M1 ↔ Kea | 525 |
| 5 | DHCPACK: `sesion` ACTIVA (SessionReady) | M1 | 520, 110 por curso, 200, 190 |
| 6 | Primer tráfico: el kernel pide la MAC del gateway y M1 responde 02:00:00:00:00:10 | M1 (proxy ARP) | 800 |

*Tabla R1.1. Fases del login.*

### 3.2. Reglas de la sesión

En `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
ovs-ofctl -O OpenFlow13 --names --rsort=priority dump-flows as1 | grep -E "00:00:00:10:01:01|10\.17\.0\."
```

| Prio | Tabla | Match | Acción | Función |
|---|---|---|---|---|
| 525 | 0 | in_port=as1-eth2, dl_src=MAC h1, udp, tp_dst=67 | CONTROLLER | DHCP → relay de M1 |
| 520 | 0 | in_port=as1-eth2, dl_src=MAC h1, ip, nw_src=IP h1 | push_vlan, VLAN 101, goto 1 | Binding puerto + MAC + IP |
| 505 | 0 | in_port=as1-eth2, dl_src=MAC h1, ip | CONTROLLER | IP distinta de la de la sesión |
| 110 | 1 | ip, nw_src=IP h1, nw_dst=10.200.2.10, tcp, tp_dst=8181 | goto 2 | Servidor de TEL354 (inscrito) |
| 200 | 2 | dl_vlan=101, ip, nw_dst=IP h1 | pop_vlan, dl_dst=MAC h1, output:as1-eth2 | Entrega por IP |
| 190 | 2 | dl_vlan=101, dl_dst=MAC h1 | pop_vlan, output:as1-eth2 | Entrega por MAC |

*Tabla R1.2. Las 6 reglas de la sesión de alumno.demo.*

Todas llevan `hard_timeout` igual al Session-Timeout restante (28 800 s para ALUMNO) y cookie `appId | 0x02 | sesion.id | índice`. Por ejemplo, `0xac020000001600` es appId 0xac (tel354.m1), tipo 0x02, sesión 22 (0x16) e índice 0 (regla 525).

En `onos>` ejecutar:
```
flows -s added of:0000000000000001
hosts
```

Las reglas de M1 aparecen con `appId=tel354.m1`. En `hosts` (y en la GUI) es normal que h1 aparezca **dos veces**, con la misma MAC: con `vlan=None` (Packet-In de EAPOL, DHCP y ARP, antes de la 520) y con `vlan=101` (captura 55, después de la 520). ONOS identifica cada host por MAC y VLAN; ningún módulo usa esa vista, porque las sesiones las lleva M1.

### 3.3. Sesión en la base de datos

En `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
PGPASSWORD=m1-demo psql "host=10.200.0.12 dbname=campus user=onos_m1 sslmode=require" -c "SELECT s.id, u.uid_ldap, r.nombre AS rol, d.mac, s.switch_dpid, s.puerto, s.ip, s.vlan_id, s.estado, s.motivo_cierre, s.inicio, s.fin FROM sesion s JOIN usuario u ON u.id = s.usuario_id JOIN rol r ON r.id = s.rol_efectivo_id JOIN dispositivo d ON d.id = s.dispositivo_id ORDER BY s.id DESC LIMIT 10;"
```

Esperado: la sesión de alumno.demo en ACTIVA, con su MAC, puerto, IP y VLAN 101. Las horas están en **UTC** (7 h más que la hora local de la VM).

### 3.4. Más usuarios

Una ventana por host, con `entrar.sh hX`:

| Host | Usuario | IP esperada | Reglas de su sesión |
|---|---|---|---|
| h2 | docente.demo | 10.49.0.x/12, gateway 10.48.0.1 | 6 (con una 110 de TEL354) |
| h3 | ti.demo | 10.82.0.x/12, gateway 10.80.0.1 | 5 (sin cursos) |
| h4 | jp.demo | 10.34.0.x/12 | Según sus inscripciones |
| h4 | sancion.demo | 10.114.0.x/12, VLAN 999 | Sin reglas 110; aislado por las reglas 58 y 57 |

*Tabla R1.3. Rol efectivo por usuario.*

---

## 4. Confidencialidad de las credenciales

| Tramo | Protocolo | Protección |
|---|---|---|
| Host ↔ ONOS | EAPOL con EAP-TTLS (TLS 1.2) | Túnel de extremo a extremo hasta FreeRADIUS; en claro solo viaja `anonymous`. El supplicant verifica la CA y el nombre del servidor |
| ONOS ↔ FreeRADIUS | RADIUS UDP 1812 | Túnel ilegible; integridad con Response Authenticator y Message-Authenticator |
| FreeRADIUS ↔ OpenLDAP | LDAPS con `require_cert = demand` | TLS |
| M1 ↔ PostgreSQL | JDBC con `sslmode=verify-ca`, `hostssl` | TLS |
| Almacenamiento | LDAP | SHA-512 crypt |

*Tabla R1.4. Cifrado por tramo.*

**Prueba.** Se captura el enlace de ONOS durante 60 s mientras alguien hace login en otra ventana. En `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
bash scripts/evidencias_r1r2.sh --radius 60 'Alumno2026!' alumno.demo
```

Durante esos 60 s, cerrar y abrir la sesión de h1 (`logout.sh` y `entrar.sh h1`). Esperado en `evidencias/<fecha>/resumen.txt`: **0 apariciones de la contraseña** y al menos 1 del usuario, solo en el Access-Accept. **Referencia (19/10):** 12 paquetes (6 rondas), `anonymous` en los 6 Access-Request y Message-Authenticator en los 12.

---

## 5. Rechazos con M1

Desde un host sin sesión (por ejemplo h3 o h4), con `entrar.sh hX`, probar:

| Caso | Credencial | Esperado |
|---|---|---|
| Clave incorrecta | `ti.demo` con otra clave | «Credenciales rechazadas.», sin IP ni reglas |
| Cuenta inactiva | `inactivo.demo` / `Inactivo2026!` | Rechazo |
| Usuario inexistente | `noexiste` | Rechazo |

En la ventana raíz, para ver los rechazos en el log de ONOS, en `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
grep -h "Access-Reject" /opt/onos/apache-karaf-*/data/log/karaf.log* | tail
```

**Referencia (19/10):** 5/5 Access-Reject, todos con el puerto cerrado (sin reglas de sesión).

---

## 6. Invitado con credencial temporal

TI crea la cuenta; la contraseña se genera al azar y solo se muestra una vez. En `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
python3 r1-identidad/servicios/ldap/crear_invitado.py --horas 1 --nombre "Visitante"
python3 r1-identidad/servicios/ldap/crear_invitado.py --listar
```

Con esa cuenta, en h4 (`entrar.sh h4`), esperado:

- IP 10.98.0.x/12 (rol k = 6, pabellón 2) y gateway 10.96.0.1;
- 5 reglas de sesión con `hard_timeout` igual a la vigencia restante, sin reglas 110;
- solo DNS e Internet: todo acceso a 10.200.x.y cae en la captura 55.

Para verificar el Session-Timeout, en `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
grep -h "Access-Accept invitado" /opt/onos/apache-karaf-*/data/log/karaf.log* | tail -3
ovs-ofctl -O OpenFlow13 --names dump-flows as2 | grep 00:00:00:60:02:02
```

Para revocar la cuenta (cambiar el uid por el que entregó el script), en `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
python3 r1-identidad/servicios/ldap/crear_invitado.py --revocar invitado-1356
```

Luego cerrar la sesión de h4 (`logout.sh`) e intentar entrar de nuevo: debe ser rechazada.

**Referencia (19/10):** Session-Timeout de 3 419 s (la vigencia que le quedaba), 5 reglas con ese `hard_timeout`, 0 de 9 recursos internos (45/45 intentos bloqueados) y Access-Reject tras la revocación. La revocación no cierra una sesión ya abierta; eso llegará con `revocar` de M1 (P6).

---

## 7. Cierre y expiración

### 7.1. Logout

En `[h1] /home/mininet/tel354-campus-sdn#` ejecutar:
```bash
bash r1-identidad/host/logout.sh
```

Envía EAPOL-Logoff y DHCPRELEASE, quita la IP y vacía la caché ARP. Esperado: el `dump-flows` de la sección 3.2 ya no muestra reglas de h1 y la sesión queda **CERRADA** con motivo **DESCONEXION**.

### 7.2. Expiración

Se baja el Session-Timeout de ALUMNO a 120 s. En `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
su postgres -c "psql campus -c \"UPDATE rol SET session_timeout=120 WHERE nombre='ALUMNO'\""
```

Entrar en h1 (`entrar.sh h1`) y esperar 2 minutos. Luego, en `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
grep -h "EXPIRACION" /opt/onos/apache-karaf-*/data/log/karaf.log | tail -3
grep -h "rebotado" /opt/onos/apache-karaf-*/data/log/karaf.log | tail -3
su postgres -c "psql campus -c \"UPDATE rol SET session_timeout=28800 WHERE nombre='ALUMNO'\""
```

Esperado: la sesión pasa a CERRADA con motivo EXPIRACION, se retiran sus reglas y M1 rebota el puerto (1,5 s apagado). **Referencia (19/10):** ACTIVA a los 0,54 s y CERRADA a los 120,3 s.

> **Hallazgo:** tras el rebote, en Mininet el host **no** reinicia 802.1X ni DHCP: conserva su IP sin sesión y sin conectividad (fallo seguro). Para volver a usarlo, `logout.sh` y `entrar.sh h1`. La corrección (P8) va en M1 v1.1.

---

## 8. Dos sesiones del mismo usuario y límite de sesiones

ALUMNO admite 2 sesiones simultáneas. Kea asigna la IP por MAC, así que dos equipos con el mismo usuario en el mismo pabellón reciben IP distintas.

1. Cerrar la sesión de h2 si la tiene. En `[h2] /home/mininet/tel354-campus-sdn#` ejecutar:
   ```bash
   bash r1-identidad/host/logout.sh
   ```
2. Entrar con **alumno.demo** en h1 y en h2 (`entrar.sh h1` y `entrar.sh h2`).
3. Verificar: `ip -br addr` en cada host (dos IP distintas de 10.17.0.0/16) y dos filas ACTIVA de alumno.demo en `sesion` (sección 3.3).
4. **Límite:** entrar con alumno.demo en h3 (as2, IP 10.18.0.x). La sesión más antigua (h1) debe cerrarse con motivo **LIMITE_SESIONES**: sus reglas desaparecen, h1 queda sin conectividad y su puerto **no** se rebota.

En `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
grep -h "superó" /opt/onos/apache-karaf-*/data/log/karaf.log | tail -3
grep -h "rebotado" /opt/onos/apache-karaf-*/data/log/karaf.log | tail -3
```

Esperado: el mensaje «M1: alumno.demo superó 2 sesiones; se cerró la sesión N» y **ningún** rebote nuevo en ese instante. Requiere M1 compilado con el zip del 23/10 o posterior.

---

## 9. Latencia de admisión

Hacer varios logins y logouts (al menos 10) en h1, h2 y h3. Luego, en `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
python3 r1-identidad/tests/latencia_m1.py
```

El script empareja en `karaf.log*` «EAPOL-Start», «AUTENTICADA» y «ACTIVA … (SessionReady)» de cada MAC. Da dos columnas:

| Tramo | Qué incluye | Estimado (AVZ08) | Medido (19/10) |
|---|---|---|---|
| EAPOL-Start → AUTENTICADA | 802.1X completo, con el camino host–switch–ONOS | 49 ms | Mediana 59,5 ms; p90 113 ms |
| EAPOL-Start → SessionReady | Además DHCP, binding y reglas | ≈ 79 ms | 102–164 ms en 10 logins y 606–698 ms en 12 (espera de 0,5 s del script anterior) |

*Tabla R1.5. Latencia de admisión.*

Con `login.sh` del 23/10 (sondeo cada 0,1 s) se espera un solo grupo del orden de 120 ms: **esta medición está pendiente de repetir**. La meta de diseño es media < 500 ms y p95 < 1 s.

---

## 10. Registro de evidencias

En `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
bash scripts/evidencias_r1r2.sh
```

Guarda en `evidencias/<fecha>/` el `resumen.txt` (flows frente al modelo, reglas por app y estado en ONOS, tabla `sesion` y latencia) y el `dump-flows` de cada switch. Es la fuente de las tablas de resultados de los documentos.

---

## 11. Resultados

| Métrica | Prueba | Referencia (19/10) | Obtenido |
|---|---|---|---|
| Tasa de bloqueo | Secciones 1 y 5 | 3/3 con `eapol_test`; 5/5 con M1 | |
| Rol correcto | Secciones 1 y 3.4 | 5/5 | |
| Latencia de servicios | Sección 1 | Mediana 11,8–14,0 ms | |
| Latencia 802.1X | Sección 9 | Mediana 59,5 ms | |
| Latencia hasta SessionReady | Sección 9 | ≈ 120 ms sin la espera del script (por repetir) | |
| Contraseña en la captura | Sección 4 | 0 de 12 paquetes | |
| Invitado temporal | Sección 6 | 3 419 s; 45/45 bloqueados; rechazo tras revocar | |
| Expiración | Sección 7.2 | 120,3 s | |
| Límite de sesiones sin rebote | Sección 8 | Por validar | |

*Tabla R1.6. Registro de resultados de R1.*

---

## 12. Problemas frecuentes de R1

| Síntoma | Causa | Acción |
|---|---|---|
| «Sin respuesta del autenticador» | M1 no activo o regla 900 ausente | `apps -s -a`; `log:display \| grep M1`; ver `/run/tel354/wpa-h1-eth0.log`; ejecutar `onos_static.py` |
| «M1: núcleo no iniciado …; se reintenta en 10 s» | Sin reglas del core o servicios caídos | Correr `onos_static.py` y revisar svc: M1 se recupera solo |
| `login.sh`: «No se pudo iniciar wpa_supplicant» con `(as1)` | Versión antigua del script | Usar `entrar.sh` y `login.sh` vigentes |
| «Credenciales rechazadas» | Clave incorrecta, cuenta inactiva o invitado vencido o revocado | Revisar la tabla de usuarios del manual general y `crear_invitado.py --listar` |
| Autenticación correcta, pero «sin respuesta del servidor DHCP», o la IP se pierde a la hora | Kea o PostgreSQL fuera de UTC (DHCPNAK) | `setup_db.sh` vigente; Kea con `TZ=UTC`; `DELETE FROM lease4`; `rm /run/dhclient-h1-eth0.leases` |
| `dhcp_relay_test.py`: dirección en uso | M1 ya ocupa 172.16.0.10:67 | `app deactivate tel354.m1` en `onos>` |
| Tras una expiración el host conserva su IP pero no tiene red | El rebote no hace que wpa_supplicant se reautentique en Mininet | `logout.sh` y `entrar.sh hX`; se corrige con P8 |
| Un equipo desplazado por el límite de sesiones queda sin red | Comportamiento esperado (LIMITE_SESIONES) | Volver a entrar con `entrar.sh hX` |
| `latencia_m1.py` no encuentra logins | El log rotó o no hubo logins con M1 | Hacer logins nuevos; el script lee `karaf.log*`, o indicar la ruta con `--log` |
| h1 aparece dos veces en la GUI, una «unknown» | ONOS identifica hosts por MAC y VLAN | Normal (sección 3.2) |

*Tabla R1.7. Síntomas, causas y acciones.*
