# Plan de implementación de los módulos M1 a M8

TEL354 · Grupo 01 · 23/10/2026. Documento de referencia para implementar los módulos que faltan. Resume los acuerdos vigentes; el detalle de diseño está en `r3-ataques-encubiertos/docs/LLSD_R3.md` (LLSD de R3), en el AVZ07 (LLSD de R1 y R2) y en `docs/PENDIENTES.md` (estado consolidado).

## 1. Estado de los módulos

| Módulo | App de ONOS | Requisito | Estado | Carpeta |
|---|---|---|---|---|
| tel354-api | bundle común | R3 a R5 | **Por implementar** (primero) | `comun/tel354-api/` |
| M1 · Identidad y sesiones (con el mínimo de M8) | `tel354.m1` | R1 | **Implementado y validado** (19/10); falta la versión 1.1 | `r1-identidad/onos-app-m1/` |
| M2 · Autorización | `tel354.m2` | R2, R3 | Parte proactiva en `onos_static.py`; **falta la app** (slow-path y, según la propuesta del 26/10, la Tabla 1 proactiva) | `r2-autorizacion/onos-app-m2/` |
| M3 · Monitoreo | `tel354.m3` | R3 a R5 | **Por implementar** | `r3-ataques-encubiertos/onos-app-m3/` |
| M4a · Detección de amenazas | `tel354.m4a` | R3, R5 | **Por implementar** | `r3-ataques-encubiertos/onos-app-m4a/` |
| M4b · Detección de anomalías | `tel354.m4b` | R4 | Por diseñar (LLSD de R4) | `r4-ddos/` |
| M5 · Mitigación | `tel354.m5` | R3 a R5 | **Por implementar** (parte de R3) | `r3-ataques-encubiertos/onos-app-m5/` |
| M6 · Auditoría | `tel354.m6` | Transversal | **Por implementar** | `r3-ataques-encubiertos/onos-app-m6/` |
| M7 · Programación de flujos | (`onos_static.py`) | Transversal | Cubierto por script REST; **propuesta (por confirmar):** app que instala el régimen base al conectarse cada switch | `comun/onos-app-base/` (propuesta) |
| M8 · Conectividad | (en M1 y `onos_static.py`) | R1, R2, R5 | Proxy ARP del gateway dentro de M1; reglas del core por script | — |
| Adaptador del IDS | script externo | R3 (complemento), R5 | Posterior | `r5-perimetral/` |

**Decisión vigente para M7 y M8.** En la maqueta, el régimen base (cookies 0x01, 0x03 a 0x05) lo instala `onos_static.py` por la API REST de ONOS, con el appId de REST. Usa la API pública, así que respeta la consigna; queda como simplificación aceptada (PENDIENTES, F.4 #10). Convertirlos en apps no bloquea R3.

**Propuesta del 26/10 (por confirmar).** Para que todo el plano de control sea dinámico, reemplazar `onos_static.py` por dos apps: **M2** asume la Tabla 1 proactiva (reglas 100 y comunes) y la recalcula con `policy_changed`, y una app **M7/M8** instala el régimen base y los caminos fijos cuando ONOS detecta cada switch (`DeviceListener`), con el appId y la cookie de su módulo. `onos_static.py` quedaría solo para diagnóstico. Las reglas siguen siendo proactivas (su número no depende de los usuarios); lo que cambia es que las gestiona el controlador.

## 2. Acuerdos comunes a todos los módulos

1. **Sin tocar ONOS.** Cada módulo es una app propia (bundle OSGi empaquetado como `.oar`) que corre dentro de Karaf. Solo se usa la API pública de ONOS (`org.onosproject.net.*`, `core`, `cfg`, `store.service`); nada de clases `*.impl` ni almacenes internos. Los ajustes del controlador van por `cfg set`.
2. **Servicios por inyección.** Los servicios de ONOS y los de nuestros módulos se obtienen con `@Reference`; cada módulo publica los suyos con `@Service`.
3. **Contrato en `tel354-api`.** Los eventos propios extienden `AbstractEvent` y cada servicio expone `addListener` con una implementación sobre `AbstractListenerManager`. Ningún módulo importa clases internas de otro: solo las de `tel354-api`.
4. **Cookie.** `appId (63–48) | tipo (47–40) | id (39–8) | índice (7–0)`. Tipos: 0x02 sesión (M1; DROP 150 con el appId de M2), 0x06 contención (M5), 0x07 mitigación (M5, R4). Se retiran reglas por cookie con `getFlowEntriesById(appId)`.
5. **Hilos.** Los listeners de ONOS no se bloquean: cada app atiende sus eventos en un ejecutor propio de un solo hilo (como M1). JDBC y Flow-Mod nunca en el hilo de eventos de ONOS.
6. **Configuración.** Parámetros en network config con la clave del appId (`tel354.m4a`, `tel354.m5`…), cargados por el script de instalación. Cambiar un umbral no exige recompilar.
7. **Base de datos.** Un rol por módulo con permisos mínimos y conexión TLS (`hostssl`, `sslmode=verify-ca`): `onos_m1`, `onos_m2`, `onos_m5` (nuevo), `onos_m6`. M3 y M4a no usan la base de datos.
8. **Estructura del código.** Igual que M1: `nucleo/` (lógica sin dependencias de ONOS, probable con un banco de pruebas) y `onos/` (adaptador que conecta el núcleo con los servicios). Un `instalar_mX.sh` por app (compila, carga la network config, instala y activa).
9. **Registro para medir.** Mensajes de log con prefijo del módulo y marca de evento (`M2: AuthorizationDenied …`, `M4a: ThreatSuspected …`, `M5: ContainmentApplied …`), para que `medir_r3.py` calcule t_det y t_mit como `latencia_m1.py`.
10. **Rebote del puerto.** Solo al expirar, al revocar y al pasar a cuarentena; nunca al cerrar por límite de sesiones (corregido en el código el 23/10).

## 3. Orden de implementación

| Paso | Entregable | Prueba de aceptación |
|---|---|---|
| 0 | M1: correcciones de R1 pendientes (meter `ctl_P` en 525 y 505; cierre por enlace caído y por 60 s sin DHCP) | `dump-flows` muestra `meter:` en 525 y 505; desconectar el cable cierra la sesión (DESCONEXION) |
| 1 | `tel354-api`; M1 v1.1 con P1, P2, P3 y P5 | `nmap -sn` dentro del /12 no ve hosts; un spoof deja BindingViolation en el log |
| 2 | M2 (slow-path, DROP 150, AuthorizationDenied) | `bateria_r2.py` sigue en 100 %; aparecen reglas 150 de 8 s |
| 3 | M6 (auth_log) | Denegaciones y violaciones registradas en `auth_log` |
| 4 | M4a (ventanas W₁ y W₂) | Casos E1 a E8 publican ThreatSuspected con la métrica correcta |
| 5 | M5 en OBSERVACION, con API REST y comandos `tel354:*` | Incidentes con `nivel_calculado`; `tel354:incidentes` los lista |
| 6 | M5 en ACTIVO; M3 | E1, E3, E9 a E11 con sus reglas, renovaciones y vencimientos |
| 7 | M1: revocación y paso forzado (P6) y reautenticación tras el rebote (P8) | E6 y E12 terminan en la VLAN 999 |
| 8 | Regresión y medición | R1 y R2 sin cambios (`evidencias_r1r2.sh`); Tabla 26 del LLSD completa con `medir_r3.py` |

## 4. Ficha de cada módulo pendiente

### 4.1. tel354-api (bundle común)

- **Qué es:** un bundle sin lógica, solo interfaces y clases de eventos. Lo importan todas las apps de R3.
- **Contenido:**
  - Eventos: `SessionEvent` (SESSION_READY, SESSION_CLOSED, AUTH_FAILED, BINDING_VIOLATION, ARP_PROBE), `AuthorizationEvent` (AUTHORIZATION_DENIED), `ThreatEvent` (THREAT_SUSPECTED, actualización), `CounterEvent` (COUNTER_DELTA), `IncidentEvent` (CONTAINMENT_APPLIED, CONTAINMENT_LIFTED, QUARANTINE_CONFIRMED).
  - Servicios: `SessionService`, `AuthorizationService`, `ThreatService`, `CounterService`, `MitigationReserveService`, `IncidentService`, `IncidentAdminService` (LLSD, Tabla 8).
  - Tipos de apoyo: `SesionInfo`, `Motivo`, `Modo`, `Filtro`, `Requisito`.
- **Empaquetado:** `packaging=bundle` con `Export-Package: pe.edu.pucp.tel354.api.*`. Se instala antes que las apps (`bundle:install` o como dependencia en el `.oar` de cada app).

### 4.2. M1 v1.1 (cambios sobre la app existente)

| Cambio | Qué se hace | Servicio de ONOS |
|---|---|---|
| P1 | Proxy ARP responde solo por el gateway 10.‹b›.0.1; cualquier otra consulta publica ArpProbe sin responder | PacketService |
| P2 | Packet-In IPv4 sin VLAN que no es DHCP (regla 505) con sesión ACTIVA → BindingViolation (IP) | PacketService |
| P3 | ARP con emisor distinto de la IP de la sesión → BindingViolation (ARP); marca `inequivoca` si suplanta gateway, servidor o plano de control | PacketService |
| P5 | Implementar `SessionService` y publicar SessionReady/SessionClosed/AuthFailed | — |
| P6 | `revocar(id, motivo, solicitante)` y `aplicarCuarentena(usuario, incidente)` con `lease4-del` en Kea (Control Agent) y rebote del puerto | DeviceAdminService |
| P8 | Al rehabilitar el puerto, Packet-Out de EAP-Request/Identity (reintentos cada 30 s, hasta 3); en `login.sh`, acción de `wpa_cli -a` que reinicia dhclient al completar 802.1X | PacketService |
| R1 pendiente | Meter `ctl_P` en las reglas 525 y 505; cierre por PORT_UPDATED (enlace caído) y por 60 s sin DHCP en AUTENTICADA | MeterService, DeviceService |

### 4.3. M2 · slow-path de la regla 55

- **Servicios de ONOS:** PacketService (procesador para IPv4 **con** VLAN de un puerto de acceso), FlowRuleService.
- **Usa:** `SessionService.getSessionByIp` (M1). Matriz en caché desde `v_permiso_efectivo` y `v_acceso_curso` (rol `onos_m2`, solo lectura).
- **Lógica:** sesión válida → si la matriz permite el acceso, reinstala el permiso y hace Packet-Out (carrera con un cambio de política); si no, instala DROP 150 (cuádrupla, hard = 8 s, cookie M2 | 0x02 | sesion.id | contador) y publica AuthorizationDenied.
- **Ciclo de vida:** con SessionClosed retira por cookie los DROP 150 de esa sesión.
- **Implementa:** `AuthorizationService`.
- **Fase posterior (opcional):** asumir la parte proactiva de `onos_static.py` (reglas 100 y Tabla 1 común) y atender `policy_changed`.

### 4.4. M3 · lectura de contadores

- **Servicios de ONOS:** FlowRuleService (`getFlowEntries(deviceId)`, estadísticas que ONOS ya sondea cada 5 s; comprobar `flowPollFrequency`).
- **Implementa:** `CounterService` (`vigilar`, `dejarDeVigilar`).
- **Lógica:** cada 5 s compara `packets()` de cada regla vigilada con la lectura anterior; si aumentó, publica CounterDelta.
- **Uso en R3:** M5 le pide vigilar la regla 301 de cada sesión en N2 para renovar la contención. M4b lo reutilizará en R4.

### 4.5. M4a · ventanas deslizantes

- **Servicios de ONOS:** ninguno (solo `CoreService`); en una fase posterior, recurso REST para IdsAlert.
- **Consume:** AuthorizationDenied (M2), BindingViolation y ArpProbe (M1), SessionClosed (M1).
- **Estructuras en memoria:** `destinos[s]`, `puertos[s][d]`, `eventos[s]`, `origenes[d]`, cada elemento con su última marca de tiempo; purga perezosa y limpieza cada 60 s. Topes: 24 destinos, 30 puertos por destino, 30 eventos, θ_F orígenes.
- **Métricas:** P, D en W₁ = 10 s y W₂ = 600 s; N y F en W₂. Umbrales 10/30, 8/24, —/30, —/5 (3 en la maqueta), por network config.
- **Publica:** ThreatSuspected (y actualizaciones sin repetir la misma alarma durante una ventana). BindingViolation se reenvía de inmediato como SUPLANTACION.
- **Implementa:** `ThreatService`.

### 4.6. M5 · evaluación, contención y confirmación

- **Servicios de ONOS:** FlowRuleService, MeterService (meter `contencion` de 1 Mbps por switch, creado en la primera N2), framework REST (`AbstractWebResource`, contexto `/onos/campus`) y consola Karaf (`AbstractShellCommand`, ámbito `tel354`).
- **Consume:** ThreatSuspected (M4a), CounterDelta (M3), SessionReady y SessionClosed (M1).
- **Lógica:** tipo de ataque → nivel (Bajo, Intermedio, Fuerte); criticidad del destino por prefijo; matriz → N0, N1 o N2; reincidencia en 24 h sube un nivel; inserta el incidente (`INSERT … RETURNING id`), reserva entradas e instala las reglas 300–302 con cookie M5 | 0x06 | incidente.id | índice. Modos ACTIVO y OBSERVACION.
- **Implementa:** `MitigationReserveService` (96 entradas por switch, compartida con R4), `IncidentService`, `IncidentAdminService` (listar, detalle, confirmar, descartar, modo).
- **API REST:** `GET /onos/campus/v1/incidentes`, `GET …/incidentes/{id}`, `POST …/incidentes/{id}/confirmar`, `POST …/incidentes/{id}/descartar`, `PUT …/r3/modo`.
- **Comandos:** `tel354:incidentes`, `tel354:incidente`, `tel354:incidente-confirmar`, `tel354:incidente-descartar`, `tel354:r3-modo`, `tel354:reserva`.
- **Base de datos:** rol nuevo `onos_m5` (LLSD, sección 6.2) en un script `comun/db/05_r3.sql`.

### 4.7. M6 · auditoría

- **Servicios de ONOS:** solo `CoreService`.
- **Consume:** todos los eventos de R3 y las operaciones de `IncidentAdminService`.
- **Lógica:** inserta en `auth_log` (rol `onos_m6`, solo INSERT) en lotes cada 1 s o cada 100 filas, desde su propio ejecutor; si la base de datos falla, retiene hasta 10 000 filas.

## 5. Estructura prevista del repositorio

```
comun/
  tel354-api/            bundle común (pom.xml, src/main/java/pe/edu/pucp/tel354/api/)
  db/05_r3.sql           rol onos_m5 e índice de reincidencia
r1-identidad/onos-app-m1/  M1 v1.1 (mismo proyecto)
r2-autorizacion/onos-app-m2/  M2 (slow-path; Tabla 1 proactiva según la propuesta)
r3-ataques-encubiertos/
  onos-app-m3/  onos-app-m4a/  onos-app-m5/  onos-app-m6/
  config/r3-netcfg.json  umbrales de M4a y parámetros de M5
  tests/medir_r3.py      t_det y t_mit desde el log de ONOS
  tests/casos_r3.md      comandos de los casos E1 a E14
  instalar_r3.sh         compila e instala tel354-api y las apps en orden
```

## 6. Compilación e instalación

En la Mininet-VM, como root (`root@mininet-vm:~/tel354-campus-sdn#`), con ONOS en marcha, ejecutar:

```bash
bash r1-identidad/onos-app-m1/instalar_m1.sh
bash r2-autorizacion/onos-app-m2/instalar_m2.sh
bash r3-ataques-encubiertos/instalar_r3.sh
```

`instalar_r3.sh` seguirá el patrón de `instalar_m1.sh`: compila `tel354-api` y cada app con Maven, carga `r3-netcfg.json`, instala y activa en este orden: tel354-api, M6, M4a, M3 y M5. M2 tiene su propio `instalar_m2.sh` en `r2-autorizacion/` y depende de `tel354-api`.

En la consola de ONOS (`onos>`), para verificar, ejecutar:

```
apps -a -s | grep tel354
bundle:list | grep tel354
```

## 7. Verificación

1. **Regresión de R1 y R2:** `scripts/evidencias_r1r2.sh` debe dar el mismo conteo de flows (42/35/31 o el que corresponda a las sesiones activas), precisión y alcance del 100 % y la misma latencia.
2. **Casos de R3:** E1 a E14 del LLSD (Tabla 25), primero con M5 en OBSERVACION y luego en ACTIVO.
3. **Medición:** `medir_r3.py` para t_det y t_mit, conteo de reglas 300–302 frente a e(N1) = 1 y e(N2) = 4, falsos positivos en E13 y trazabilidad en `auth_log`. Los resultados se registran en la Tabla 26 del LLSD de R3.

## 8. Fuera de esta etapa

- M4b y las partes de M5 para R4 (limitación 910 y 120) y R5 (bloqueos del edge): según sus LLSD.
- Adaptador del IDS Suricata (`eve.json` → API REST de M4a): posterior.
- M7 y M8 como apps propias: según la propuesta del 26/10 (sección 1), por confirmar; mientras tanto, la maqueta sigue con `onos_static.py`.
- Clúster de ONOS: las ventanas de M4a viven en memoria de una instancia (LLSD, sección 11).
