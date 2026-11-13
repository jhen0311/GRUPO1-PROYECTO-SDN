# M6 · Auditoría (auth_log) fuera de ONOS

M6 registra en `auth_log` cada evento de seguridad del campus (R1, R2 y R3), para reconstruir incidentes y evidenciar las pruebas.

> **Decisión provisional (13/11/2026).** El LLSD de R3 (R3-D3, sección 5.7) y el acuerdo 1 de `docs/PLAN_MODULOS.md` describen M6 como una app de ONOS (`tel354.m6`) suscrita a los eventos de `tel354-api`. Por ahora se usa esta versión fuera de ONOS, ya avanzada y probada; el grupo decidirá si se mantiene así o pasa a ser una app. Mientras tanto se respeta el resto de lo acordado: `onos_m6` con solo INSERT, escritura por lotes y los nombres de evento de la Tabla 17 del LLSD. A diferencia de M1–M5, **no es una app de ONOS**: es un servicio Python en el **host de VM-CLUSTER** (172.16.0.10), al lado de los contenedores `onos-1..3`. Usa la misma red in-band que ya tiene ese host hacia PostgreSQL (10.200.3.12:5432, rol `onos_m6`, ya permitido en `pg_hba` por `setup_db.sh`). No ocupa una VM más (el slice sigue en 14).

## Dos módulos

| Archivo | Qué hace |
|---|---|
| `m6_colector.py` | **De dónde salen los eventos** y servicio principal. (1) Sigue `docker logs -f --timestamps` de cada instancia y convierte las líneas con marca de módulo (acuerdo 9 de PLAN_MODULOS) en eventos. (2) Cada 10 s consulta `GET /onos/campus/v1/incidentes` (REST de M5) y registra los cambios de estado que no aparecieron en el log. Deduplica entre instancias y guarda un punto de control por instancia. |
| `m6_registro.py` | **A dónde van.** Inserta en `auth_log` por lotes (cada 1 s o 100 filas) desde un hilo propio, con TLS (`verify-ca`). Si PostgreSQL cae, retiene hasta 10 000 filas, descarta las más antiguas y lo deja escrito (`M6_PERDIDAS`) al volver. |

## Eventos registrados

Los nombres de la LLSD de R3 (Tabla 17) más los de sesión de R1, que el README de R1 asigna a M6:

| Línea del log | `auth_log.evento` | Referencias |
|---|---|---|
| `M1: Access-Accept u rol R ...` | `AUTH_ACEPTADA` | uid en detalle |
| `M1: Access-Reject ...` / `sin fila activa` / `no es un switch de acceso` | `AUTH_FALLIDA` | motivo |
| `M1: AUTENTICADA / ACTIVA / CERRADA sesion N [...]` | `SESION_AUTENTICADA` / `SESION_ACTIVA` / `SESION_CERRADA` | sesion_id |
| `M1: BindingViolation (IP\|ARP) sesion N ...` | `SUPLANTACION` | sesion_id; ip_usada, ip_concedida, inequívoca |
| `M1: revocación de la sesión N ...` | `SESION_REVOCADA` | sesion_id |
| `M1: incidente I: k sesiones del usuario U pasan a cuarentena` | `CUARENTENA_APLICADA` | usuario_id |
| `M2: AuthorizationDenied sesion N ... -> ip proto P puerto X` | `AUTORIZACION_DENEGADA` | sesion_id; ip_dst, proto, puerto, dpid |
| `M4a: ThreatSuspected M=v/u W=Ns sesion N -> ...` | `SOSPECHA` | sesion_id; métrica, valor, umbral, ventana |
| `M4a: IdsAlert sid S sev V tipo T sesion N [...] -> ip proto P puerto X sensor Z: firma` | `IDS_ALERTA` | sesion_id; firma_sid, severidad, tipo, ip_dst, puerto, firma |
| `M4a: alerta del IDS sin sesión para IP (sid S)` | `IDS_ALERTA` | ip_origen, firma_sid, motivo SIN_SESION |
| `M5: incidente I [...] ... -> N1\|N2 ...` / `sube de A a B` | `CONTENCION_APLICADA` | incidente_id, nivel |
| `M5: incidente I [...] venció sin renovación` | `CONTENCION_RETIRADA` | motivo VENCIMIENTO |
| `M5: incidente I CONFIRMADO por ... : cuarentena de u hasta T` | `CUARENTENA` + `ADMIN_INCIDENTE` | fin, solicitante, vía |
| `M5: incidente I DESCARTADO por ...` / `modo M fijado por ...` | `ADMIN_INCIDENTE` | operación, solicitante, vía |
| Cambio de estado visto solo en la REST de M5 | `INCIDENTE_ESTADO` | estado anterior y nuevo |

ArpProbe no se registra (LLSD R3, 5.7: un barrido daría una fila por dirección).

**Requisito en M2:** las denegaciones solo llegan a M6 si M2 las escribe en el log con `info`. La versión de M2 de esta rama ya lo hace (`M2: AuthorizationDenied ...`); la plantilla anterior usaba `debug`.

## Referencias de auth_log

`onos_m6` solo tiene INSERT en `auth_log`, como se acordó. `usuario_id` y `dispositivo_id` los completa la base con el disparador `auth_log_referencias` de `comun/db/06_m6.sql` (`SECURITY DEFINER`; `setup_db.sh` lo carga): toma los datos de `sesion` a partir de `sesion_id`, o el `usuario.id` del uid que M6 deja en `detalle.usuario`. Así M6 no necesita leer ninguna tabla. Si un `sesion_id` no existe, la fila se guarda igual, con el id en `detalle.referencia_rota`.

## Instalación

En `root@controller:~/tel354-campus-sdn#` (host de VM-CLUSTER) ejecutar:

```bash
export TEL354_ENV=una_instancia.env                                # perfil del slice actual (una instancia)
bash r3-ataques-encubiertos/m6-auditoria/instalar_m6.sh          # pruebas, archivos, servicio systemd
journalctl -u m6-auditoria -f
```

La configuración queda en `/etc/tel354/m6.env`, creada la primera vez desde `m6.env.ejemplo` y ajustada al perfil (contenedor `onos` y REST en 127.0.0.1 con una instancia; `onos-1..3` en la red Docker con el clúster) y el punto de control en `/var/lib/tel354/m6-estado.json`.

Para probar sin base de datos:

```bash
python3 r3-ataques-encubiertos/m6-auditoria/m6_colector.py --stdout --sin-rest       # imprime cada evento en JSON
docker logs onos-1 > /tmp/onos1.log 2>&1
python3 r3-ataques-encubiertos/m6-auditoria/m6_colector.py --archivo /tmp/onos1.log --stdout
```

## Pruebas

| # | Qué | Cómo | Esperado |
|---|---|---|---|
| M6-1 | Unitarias | `python3 r3-ataques-encubiertos/m6-auditoria/tests/test_m6.py` | 14 pruebas OK |
| M6-2 | Login | `entrar.sh h1` | `AUTH_ACEPTADA`, `SESION_AUTENTICADA`, `SESION_ACTIVA` |
| M6-3 | Denegación (M2) | Alumno: `curl -m 3 http://10.200.1.30:5432` | `AUTORIZACION_DENEGADA` con ip_dst y puerto |
| M6-4 | Suplantación | `arpspoof` hacia el gateway | `SUPLANTACION` inequívoca |
| M6-5 | Incidente | `ataque.py` y `tel354:incidente-confirmar` | `SOSPECHA`, `CONTENCION_APLICADA`, `CUARENTENA`, `ADMIN_INCIDENTE` |
| M6-6 | Caída de la BD | Detener PostgreSQL 1 min y reanudar | Las filas del minuto aparecen al volver |
| M6-7 | Reinicio de M6 | `systemctl restart m6-auditoria` | Sin filas repetidas ni perdidas |

Consulta (como postgres en VM-IDENTIDAD):

```sql
SELECT ts, evento, sesion_id, usuario_id, detalle FROM auth_log ORDER BY id DESC LIMIT 20;
```

## Duplicados entre instancias

En el clúster, la misma línea puede aparecer en el log de más de una instancia. M6 la cuenta una vez si llega desde **otra** instancia dentro de 5 s. Dos líneas iguales de la misma instancia son dos eventos (por ejemplo, dos `Access-Reject` seguidos del mismo equipo) y se registran ambas.

## Límites conocidos

- M6 ve lo que las apps escriben en su log y lo que expone la REST de M5, no los eventos OSGi internos. Si una app cambia el texto de una línea, hay que ajustar su expresión en `REGLAS` (las pruebas usan los formatos actuales).
- Verificado con PostgreSQL 16 y el esquema real (`01_schema.sql`, `02_roles.sql`, `03_seed_demo.sql`). Falta probarlo con los contenedores de ONOS en el slice (M6-2 a M6-7).
