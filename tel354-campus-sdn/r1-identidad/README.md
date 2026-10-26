# R1 · Control de acceso de usuarios válidos según su rol

Un equipo conectado a un puerto de acceso no tiene red hasta que su usuario se autentica con 802.1X. Con el Access-Accept, M1 abre la sesión con el rol que resolvió PostgreSQL, el relay DHCP le entrega una IP del pool de ese rol y de su pabellón, y M1 instala las reglas de la sesión. Desde ahí, R2 decide a qué recursos llega.

## M1 como app de ONOS (`onos-app-m1/`)

M1 es una app OSGi propia que usa solo servicios nativos de ONOS 1.13 y no modifica el controlador:

| Servicio de ONOS | Uso en M1 |
|---|---|
| PacketService | Packet-In de las reglas 900 (EAPOL), 525 (DHCP) y 800 (ARP); Packet-Out hacia el host |
| FlowRuleService | Reglas de sesión con cookie 0x02 y hard_timeout; aviso de FLOW_REMOVED al expirar |
| DeviceAdminService | Rebote del puerto al expirar la sesión (fuerza la reautenticación) |
| NetworkConfigRegistry | Parámetros en `m1-netcfg.json` |
| Clases de onlab (`EAPOL`, `EAP`, `RADIUS`, `ARP`, `IPv4`, `UDP`) | Construcción y lectura de los paquetes |

La lógica está en `nucleo/` y no depende de ONOS; `onos/` la conecta con el controlador. Así el núcleo se puede probar con los servicios reales sin ONOS (ver *Banco de pruebas*).

### Por qué no la app AAA de OpenCORD

Se evaluó AAA (`org.opencord.aaa`) como autenticador nativo. Se descartó por tres motivos verificados en su código fuente:

1. **No soporta EAP-TTLS.** La única rama compatible con ONOS 1.13 es `aaa-1.10` (compilada contra ONOS 1.13.10). Su `AaaManager` solo reenvía los tipos EAP Identity, MD5 y TLS; cualquier otro termina en `log.warn("Unknown EAP packet type")`. El diseño usa EAP-TTLS/PAP (tipo 21) para validar usuario y contraseña contra LDAP, así que la autenticación se cortaría en el segundo mensaje.
2. **Su evento no identifica al usuario.** `AuthenticationEvent` solo trae el tipo (APPROVED o DENIED) y el puerto, sin MAC ni usuario.
3. **Depende de SADIS,** el inventario de abonados de OpenCORD para OLT, que no aplica a una red de campus.

M1 funciona como autenticador de paso (RFC 3579): copia el EAP del host en RADIUS y viceversa sin interpretar el método.

### Fases y reglas

| Fase | Disparador | Acción de M1 | Reglas (switch de acceso) |
|---|---|---|---|
| 1 | EAPOL-Start | EAP-Request/Identity; luego cada EAP-Response va en un Access-Request (con State, NAS-Port, Calling-Station-Id y Message-Authenticator) | — |
| 2 | Access-Accept | Lee User-Name, Filter-Id (rol), Session-Timeout y PUCP-Max-Sessions; registra `dispositivo` y `sesion` AUTENTICADA; aplica el límite de sesiones del rol | 525 (DHCP → M1), 505 (IP incoherente → controlador) |
| 3 | DHCPACK de Kea | Opción 82 con N (pabellón) y k (rol) hacia Kea; con el ACK, `sesion` ACTIVA (SessionReady) | 520 (binding puerto + MAC + IP → VLAN), 110 por servidor de sus cursos, 200 y 190 (entrega) |
| 4 | ARP del host | Responde por el gateway virtual 02:00:00:00:00:‹b› (no responde sondeos de DHCP) | — |
| Cierre | EAPOL-Logoff, nueva sesión del mismo equipo, límite de sesiones o FLOW_REMOVED por hard_timeout | Retira las reglas de la sesión y la marca CERRADA con su motivo | — |

Cuarentena e Invitado nunca reciben reglas 110. La cookie de cada regla es `appId | 0x02 | sesion.id | índice`.

## Confidencialidad de las credenciales

| Tramo | Protocolo | Protección |
|---|---|---|
| Host ↔ ONOS | EAPOL con EAP-TTLS (TLS 1.2) | Túnel de extremo a extremo entre el supplicant y FreeRADIUS; en claro solo va `anonymous`. El supplicant verifica la CA y el nombre del servidor (`domain_suffix_match`). |
| ONOS ↔ FreeRADIUS | RADIUS UDP 1812 | El túnel viaja ilegible. El Access-Accept trae User-Name y Filter-Id en claro; la integridad la dan el Response Authenticator y el Message-Authenticator (M1 verifica ambos). Para producción se propone RadSec (RFC 6614). |
| FreeRADIUS ↔ OpenLDAP | LDAPS, `require_cert = demand` | TLS |
| M1 ↔ PostgreSQL | JDBC con `sslmode=verify-ca` y `hostssl` en `pg_hba.conf` | TLS con la CA de la demo |
| Almacenamiento | LDAP | SHA-512 crypt |

El controlador nunca ve la contraseña: aunque ONOS fuera comprometido, no podría leerla.

## Uso

```bash
bash r1-identidad/onos-app-m1/instalar_m1.sh         # compilar, instalar y activar (Maven + Java 8)
bash r1-identidad/host/entrar.sh h1                  # "encender" h1: login y luego una shell
bash r1-identidad/host/logout.sh                     # dentro del host: EAPOL-Logoff y DHCPRELEASE
```

En la ventana ONOS: `apps -s -a | grep tel354` y `log:tail | grep M1`.

## Pruebas

| Prueba | Comando | Nota |
|---|---|---|
| Batería de autenticación | `python3 r1-identidad/tests/r1_radius.py --bateria` | FreeRADIUS directo (eapol_test) |
| Latencia de autenticación | `python3 r1-identidad/tests/r1_radius.py --latencia 30` | |
| DHCP con opción 82 | `python3 r1-identidad/tests/dhcp_relay_test.py --casos 1:1,3:1,5:2` | Con M1 desactivado: ambos usan 172.16.0.10:67 |
| Login de extremo a extremo | `entrar.sh h1` y luego `bateria_r2.py` | El flujo de la demo |
| Latencia EAPOL-Start → SessionReady | `python3 r1-identidad/tests/latencia_m1.py` | Lee el karaf.log de ONOS |

### Banco de pruebas sin ONOS (`onos-app-m1/banco/`)

Ejecuta el núcleo de M1 con los servicios reales y emula el switch con `puente.py`. Resultados del 18/10/2026 (Ubuntu 24.04, FreeRADIUS 3.2, PostgreSQL 16, Kea 2.4.1):

- **alumno.demo en h1:** EAP-TTLS completo en 6 rondas RADIUS, IP 10.17.0.x/12 con gateway 10.16.0.1 y 6 reglas (525, 505, 520, 200, 190 y una 110 de TEL354).
- **docente.demo en h2:** IP 10.49.0.x/12.
- **sancion.demo:** VLAN 999 y pool 10.113.0.x, sin reglas 110.
- **jp.demo y ti.demo:** pools 10.33.0.x y 10.81.0.x.
- **Reautenticación sin logoff** (`wpa_cli reauthenticate`): la sesión nueva retira las reglas de la anterior en una etapa previa y conserva la IP.
- **Clave incorrecta:** EAP-Failure y sin IP.
- **Proxy ARP:** responde 10.16.0.1 con 02:00:00:00:00:10.
- **Logout:** las 6 reglas se retiran y la sesión queda CERRADA (DESCONEXION).
- **Cifrado:** en 12 paquetes RADIUS de un login, la contraseña aparece 0 veces y el usuario 1 vez (Access-Accept). M1 conecta a PostgreSQL con TLS 1.3.
- **Cuatro logins seguidos** del mismo equipo funcionan. Antes se detectó que Kea 2.4 con leases en PostgreSQL falla al renovar o liberar si su zona horaria no es UTC; `start_services.sh` ahora lo arranca con `TZ=UTC`.

## Pendiente en R1

- Credencial temporal de invitado (cuenta en LDAP más rol INVITADO con vencimiento en PostgreSQL).
- Escritura de `auth_log` (corresponde a M6, con su propio rol de base de datos).
- Estado de sesiones en un ConsistentMap si se usa más de una instancia de ONOS (hoy en memoria, una instancia).
