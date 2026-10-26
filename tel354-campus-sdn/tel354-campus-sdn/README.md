# Red de campus SDN con enfoque Zero Trust · TEL354 Grupo 01

Prototipo de la solución de campus de TEL354 (PUCP, 2026-2): ONOS 1.13 como controlador, Open vSwitch con OpenFlow 1.3 como plano de datos y los servicios de identidad (FreeRADIUS, OpenLDAP, PostgreSQL y Kea). Ningún equipo tiene acceso a la red hasta autenticarse con 802.1X, y su acceso queda limitado a lo que permite su rol y sus cursos.

El código está organizado por requerimiento. Los módulos son apps de ONOS que usan solo los servicios nativos del controlador (PacketService, FlowRuleService, DeviceAdminService y NetworkConfig); no se modifica el código de ONOS.

## Estructura

```
tel354-campus-sdn/
├── comun/                    lo que usan todos los requerimientos
│   ├── config/demo.env       parámetros de la demo (direcciones, secretos de prueba)
│   ├── topo/                 campus_topo_demo.py: topología base en Mininet
│   ├── db/                   modelo de datos (14 tablas), roles por componente, datos de prueba
│   └── lib/onos_rest.py      cliente REST de ONOS y constructores de reglas
├── r1-identidad/             R1: autenticación y sesiones
│   ├── onos-app-m1/          M1 (app de ONOS en Java): 802.1X, sesión, relay DHCP, proxy ARP
│   ├── servicios/            ldap/, radius/, kea/
│   ├── host/                 login.sh, logout.sh y entrar.sh (el "equipo" del usuario)
│   ├── tests/                r1_radius.py, dhcp_relay_test.py y configuraciones EAP
│   └── diagnostico/          sesion_manual.py (solo para el modo de diagnóstico)
├── r2-autorizacion/          R2: acceso a recursos por rol y por curso
│   ├── proactivo/            onos_static.py: régimen proactivo (core y Tablas 0–2 de acceso)
│   └── tests/                bateria_r2.py, contar_flows.py, export_matriz.py
├── r3-ataques-encubiertos/   R3 (se deriva del escenario de R1 y R2)
├── r4-ddos/                  R4
├── r5-perimetral/            R5
├── scripts/                  instalación de dependencias, certificados y arranque de servicios
└── docs/                     MANUAL_GENERAL.md, MANUAL_PRUEBAS_R1.md, MANUAL_PRUEBAS_R2.md, PENDIENTES.md y PLAN_MODULOS.md
```

## Orden de los módulos

R1 y R2 forman el escenario base, y R3, R4 y R5 se apoyan en él (M3 consume el evento SessionReady y las estadísticas de los flows; M5 mitiga con meters o revocando la sesión a través de M1).

| Fase | Módulo | Qué ocurre | Reglas |
|---|---|---|---|
| 0. Arranque | M7 + M8 + M2 (`onos_static.py`) | Régimen proactivo: core, Tablas 0–2 de acceso, meters | 900, 800, 600, 10, 100, 60, 58, 57, 55, 50, 200, 180 |
| 1. Autenticación | M1 | EAPOL (regla 900) ↔ RADIUS hacia FreeRADIUS; LDAP valida la contraseña y PostgreSQL da el rol | — |
| 2. Sesión | M1 | Access-Accept → `sesion` AUTENTICADA y reglas del equipo | 525, 505 |
| 3. Dirección IP | M1 | DHCP (525) con opción 82 → Kea → DHCPACK → `sesion` ACTIVA | 520, 110 por curso, 200, 190 |
| 4. Gateway | M1 (mínimo de M8) | Proxy ARP del gateway virtual 02:00:00:00:00:‹b› (regla 800) | — |
| 5. Uso | M2 | Acceso a los recursos según rol (100) y cursos (110) | — |
| 6. Cierre | M1 | EAPOL-Logoff o fin del Session-Timeout → reglas retiradas, `sesion` CERRADA | — |

## Puesta en marcha (una vez por laptop)

En la Mininet-VM, como root (`sudo -i`), desde la raíz del repositorio (`/home/mininet/tel354-campus-sdn`):

```bash
bash scripts/instalar_dependencias.sh            # termina con la comprobación en OK
bash scripts/gen_certs.sh                        # CA y certificados (RADIUS, LDAP y PostgreSQL)
bash r1-identidad/servicios/ldap/setup_ldap.sh
bash comun/db/setup_db.sh
bash r1-identidad/servicios/radius/setup_radius.sh
```

ONOS 1.13.0 se instala como indica el manual general: [`docs/MANUAL_GENERAL.md`](docs/MANUAL_GENERAL.md) (paso a paso, verificaciones, problemas frecuentes y pendientes de R3–R5).

## Cada sesión de demo

1. **Ventana ONOS:** `export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 ONOS_APPS=drivers,openflow` y `/opt/onos/bin/onos-service start`.
2. **Ventana Mininet**, sin `--manual` (los hosts arrancan sin IP):
   ```bash
   export PYTHONIOENCODING=utf-8 LANG=C.UTF-8
   mn -c && python3 comun/topo/campus_topo_demo.py --services
   ```
3. **Tercera ventana:**
   ```bash
   python3 r2-autorizacion/proactivo/onos_static.py      # primero: sin el core, M1 no alcanza los servicios
   bash r1-identidad/onos-app-m1/instalar_m1.sh          # la primera vez compila; luego ONOS la recuerda
   ```
4. **Encender un equipo** (desde el namespace raíz de la VM, una ventana por host):
   ```bash
   bash r1-identidad/host/entrar.sh h1      # pide usuario y contraseña, y luego deja una shell en h1
   ```
   Usuarios de prueba: `alumno.demo / Alumno2026!`, `docente.demo / Docente2026!`, `ti.demo / TI2026!`, `jp.demo / Jp2026!`, `sancion.demo / Sancion2026!` (cuarentena).

## Verificación

| Qué | Dónde | Comando |
|---|---|---|
| Reglas de la sesión, con prioridad, tabla y puerto | VM | `ovs-ofctl -O OpenFlow13 --names --rsort=priority dump-flows as1` |
| Reglas en el controlador | Ventana ONOS | `flows -s added of:0000000000000001` |
| Sesiones | VM | `PGPASSWORD=m1-demo psql "host=10.200.0.12 dbname=campus user=onos_m1 sslmode=require" -c "SELECT * FROM sesion"` |
| Acceso a los recursos | Shell de h1 | `python3 r2-autorizacion/tests/bateria_r2.py --usuario alumno.demo` (antes, en la VM: `python3 r2-autorizacion/tests/export_matriz.py > /tmp/matriz.json`) |
| Flow entries frente al modelo del AVZ08 | VM | `python3 r2-autorizacion/tests/contar_flows.py` |
| La contraseña no viaja en claro | VM | `tcpdump -ni ctl-eth0 -A udp port 1812 \| grep -c Alumno2026` → 0 |
| Cierre de sesión | Shell de h1 | `bash r1-identidad/host/logout.sh` |

## Modo de diagnóstico

`--manual` (IP fijas y ARP estático) junto con `r1-identidad/diagnostico/sesion_manual.py` emulan el resultado de M1 sin autenticación. Sirven solo para aislar fallas: si R2 funciona en manual pero no en automático, el problema está en R1. No forman parte de la demo.
