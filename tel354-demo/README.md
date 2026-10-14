# Kit de la demo de R1 y R2 en Mininet · TEL354 Grupo 01

Este kit implementa en Mininet los servicios de R1 (identidad y admisión) y R2 (autorización por rol) del AVZ07 (LLSD), la topología base de las simulaciones y las pruebas que miden las métricas del AVZ08. R3, R4 y R5 quedan fuera.

El paso a paso completo está en el **Manual de despliegue en Mininet** del grupo. Este README resume el kit y sirve de referencia rápida.

Versión validada el 12/10/2026 en la Mininet-VM: Ubuntu 20.04, Mininet 2.3, OVS 2.13, ONOS 1.13, FreeRADIUS 3.0.20, slapd 2.4.49, PostgreSQL 12 y Kea 2.4.2.

## 1. Contenido

| Carpeta | Contenido |
|---|---|
| `topo/` | `campus_topo_demo.py`: topología base (plan de direccionamiento vigente y techo de Packet-In) |
| `tools/` | `instalar_dependencias.sh`, `gen_certs.sh`, `export_matriz.py` y `yaml2sql.py` |
| `db/` | Modelo de datos (14 tablas), roles de acceso, datos de la demo y `setup_db.sh` |
| `ldap/` | `setup_ldap.sh` (SHA-512 crypt, LDAPS, ACL y regla de AppArmor) y usuarios |
| `radius/` | FreeRADIUS: EAP-TTLS/PAP, bind LDAP y rol desde PostgreSQL |
| `kea/` | Generador de `kea-dhcp4.conf` (opción 82) y Control Agent |
| `resources/` | Arranque y parada de los servicios dentro de svc, DNS y servidores de recursos |
| `controller/` | Régimen proactivo por REST (`onos_static.py`) y emulación de las reglas de sesión de M1 (`sesion_manual.py`) |
| `tests/` | Pruebas de R1, DHCP, R2 y conteo de flow entries |
| `demo.env` | Parámetros comunes (contraseñas de demo, direcciones y ruta de los hooks de Kea) |

## 2. Instalación (una vez por laptop)

Todo se ejecuta como root (`sudo -i`) desde `/home/mininet/tel354-demo`:

```bash
bash tools/instalar_dependencias.sh   # termina con la comprobación en OK
bash tools/gen_certs.sh
bash ldap/setup_ldap.sh               # 6 usuarios y «OpenLDAP configurado»
bash db/setup_db.sh                   # «PostgreSQL 12 configurado»
bash radius/setup_radius.sh           # «Configuration appears to be OK»
```

## 3. Cada sesión de demo

1. En las tres ventanas de PuTTY, ejecutar `sudo -i` y luego `export PYTHONIOENCODING=utf-8 LANG=C.UTF-8`. PuTTY debe estar en UTF-8.
2. **Ventana ONOS:** arrancar ONOS con `ONOS_APPS=drivers,openflow` y luego:
   ```
   onos> cfg set org.onosproject.provider.host.impl.HostLocationProvider requestInterceptsEnabled false
   ```
3. **Ventana Mininet:** `mn -c` y `python3 topo/campus_topo_demo.py --services --manual`.
4. **Tercera ventana:** `python3 controller/onos_static.py`; luego `ping -c2 10.200.0.10` debe responder.
5. **Pruebas:** `tests/r1_radius.py`, `tests/dhcp_relay_test.py`, `controller/sesion_manual.py h1|h2|h3`, `tests/contar_flows.py`, `tools/export_matriz.py > /tmp/matriz.json` y, en la CLI de Mininet, `h1 python3 tests/bateria_r2.py --usuario alumno.demo` (igual con `docente.demo` en h2 y `ti.demo` en h3).
6. **Apagado:**
   ```
   mininet> svc bash /home/mininet/tel354-demo/resources/stop_services.sh
   mininet> exit
   ```
   Después `mn -c` y `logout` en ONOS.

## 4. Resultados de la validación (12/10/2026)

| Métrica (AVZ08) | Resultado |
|---|---|
| Tasa de bloqueo de R1 | 100 % (3/3) |
| Rol correcto en accesos válidos | 100 % (5/5), con doble rol y cuarentena |
| Latencia de autenticación (mediana / p95) | 11,8–14,0 ms / 13,6–17,4 ms; 6 rondas RADIUS |
| Latencia DHCP (media / p95) | 2,5 ms / 3,6 ms |
| Pool correcto por opción 82 | 100 % (60/60) |
| Flow entries as1 / as2 / core | 42 / 35 / 31, igual al modelo $F_{acc} = 28 + P + 5S + C$, $F_{core} = 31$ (con acceso por curso) |
| Precisión de R2 | 100 % (80/80), por usuario y con acceso por curso |
| Alcance de autorizados | 100 % (55/55) |

## 5. Acceso por curso (validado el 13/10/2026)

Cada usuario alcanza **solo** los servidores de los cursos en los que está inscrito en el ciclo vigente, no los de todos los cursos.

- Los recursos con `curso_id` salen de la matriz por rol: `v_permiso_efectivo` los excluye y la demo pasa de 16 a 13 reglas 100.
- La vista `v_acceso_curso` da, por usuario, los servidores de sus cursos. Al abrir la sesión se instala una **regla 110** por servidor en la Tabla 1, con match en la IP de la sesión. En la demo la instala `sesion_manual.py`; en el diseño, M1.
- Cuarentena e Invitado nunca reciben reglas 110.
- Un cambio en `inscripcion` dispara `policy_changed`.
- Datos de la demo: alumno.demo, docente.demo y jp.demo están inscritos en TEL354; nadie lleva TEL141, cuyo servidor (Nube TEL141, 10.200.2.20:443) es nuevo y debe quedar bloqueado para todos.
- Modelo por switch: $F_{acc} = 28 + P + 5S + C$, con $C = \sum c_s$ (reglas 110). Core: $5 + 7 \cdot 2 + 12 = 31$.

Quien ya tenga una versión anterior debe copiar el kit nuevo, repetir `bash db/setup_db.sh`, reiniciar los servicios en svc y reinstalar las reglas (`onos_static.py --borrar` y luego `onos_static.py`).

## 6. Diferencias con el diseño

| Diferencia | Motivo |
|---|---|
| Las reglas proactivas se cargan por REST y no desde M2, M7 y M8 dentro de ONOS. | Aún no existen las apps; el efecto en los switches es el mismo. |
| `sesion_manual.py` instala reglas permanentes y sin la cookie del diseño. | Los pondrá M1; el cierre se hace por appId. |
| La captura 55 llega a ONOS, pero sin M2 nadie instala el DROP 150. | El acceso se deniega igual; los DROP aparecerán con M2. |
| ARP estático al gateway en cada host (modo `--manual`). | Lo hará el proxy ARP de M8. |
| **La VLAN se retira en la Tabla 2 del acceso, no en el core.** | OVS, en OpenFlow 1.3, rechaza `pop_vlan` en reglas que no exigen VLAN. Ya reflejado en el AVZ07 (Tablas 13 y 14). |
| El Server Identifier se fija al relay en Kea. | Kea no aplica la sub-opción 11 (RFC 5107); el efecto es el mismo. |
| El rol se resuelve en la función `campus_rol_efectivo`. | Es la misma lógica de los pasos 1 a 4 de la sección 3.3, en una consulta. |

## 7. Usuarios de la demo

| Usuario | Contraseña | Rol |
|---|---|---|
| alumno.demo | Alumno2026! | ALUMNO (h1), inscrito en TEL354 |
| docente.demo | Docente2026! | DOCENTE (h2), inscrito en TEL354 |
| ti.demo | TI2026! | TI (h3) |
| jp.demo | Jp2026! | JP (doble rol) |
| inactivo.demo | Inactivo2026! | Rechazo |
| sancion.demo | Sancion2026! | CUARENTENA |

La identidad externa del túnel es `anonymous`. Las contraseñas de demo están en `demo.env`.

## 8. Siguiente paso

M1 como app de ONOS en Java (802.1X y relay DHCP por Packet-In). La especificación está en `PENDIENTES.md`.
