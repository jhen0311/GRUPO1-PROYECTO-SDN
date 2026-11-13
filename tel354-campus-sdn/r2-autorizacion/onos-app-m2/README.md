# tel354.m2 · M2 · Autorización (R2) dentro de ONOS 2.7.0

App OSGi propia (Java 11) que hace cumplir R2 desde el controlador: **cada usuario solo alcanza los recursos que su rol (o su inscripción) permite**. Reemplaza al puente `base_auto.py --solo-tabla1` y completa la plantilla de M2 (slow-path de la regla 55).

## Qué debe cumplir R2 como módulo dentro de ONOS

| # | Requisito | Fuente | Cómo lo cumple M2 |
|---|---|---|---|
| 1 | App propia, sin tocar ONOS: solo la API pública (`PacketService`, `FlowRuleService`, `MeterService`, `DeviceService`, `MastershipService`, `NetworkConfigRegistry`) | PLAN_MODULOS, acuerdo 1 | `tel354.m2` empaquetada como `.oar` con `onos-dependencies` 2.7.0 |
| 2 | Servicios por inyección y contrato en tel354-api | Acuerdos 2 y 3; LLSD R3, Tabla 8 | `@Reference` a `SessionService` (M1); publica `AuthorizationService` y `AuthorizationEvent` |
| 3 | **Tabla 1 proactiva**: permisos 100 desde `v_permiso_efectivo` y comunes 60, 58, 57, 55, 50 y 0 con los meters captura (50 pps) y cuarentena (1 Mbps). El número de reglas no depende de los usuarios | README de R2; LLSD R3, Tablas 7 y 12; decisión del 11/11 | `nucleo/Tabla1`: P + 8 reglas por switch de acceso (21 en el piloto); cookies 0x03 (recurso, k) y 0x04 |
| 4 | Instalarla **al detectar cada switch de acceso**, no desde un perfil | GUIA_VALIDACION, Paso 17 («eso es justamente lo que debe asumir M2») | `DeviceListener`; un switch es de acceso si tiene la regla 600 de M7 (de ahí sale también su uplink) |
| 5 | Conciliación idempotente: reinicios y reconexiones no duplican nada | Patrón de `tel354.base` | Compara lo deseado con el almacén de ONOS y envía solo la diferencia, en dos etapas |
| 6 | **Política como datos**: un cambio en `permiso_rol`, `recurso` o `inscripcion` se aplica en caliente | Informe LAB6, 3.5; AVZ07 7.8 | `LISTEN policy_changed` (triggers de `01_schema.sql`) + revisión cada 30 s |
| 7 | **Slow-path de la regla 55**: IPv4 con VLAN de un puerto de host → sesión válida → DROP 150 de 8 s con cookie (M2, 0x02, sesión, n) y `AuthorizationDenied` | LLSD R3, sección 5.3 | `Procesador` + `atender()`; una sola vez por cuádrupla mientras el DROP está vigente |
| 8 | Carrera con un cambio de política: si la matriz sí lo permite, reinstalar el permiso y reenviar | LLSD R3, sección 5.3 | Rol → reprograma la Tabla 1; curso → 110 de carrera con cookie de M2; Packet-Out sin VLAN por el uplink |
| 9 | Al cerrarse la sesión, retirar sus reglas | LLSD R3, sección 5.3 | `SessionClosed` → retira por cookie los DROP 150 y las 110 de carrera |
| 10 | Hilos propios: JDBC y Flow-Mod nunca en el hilo de eventos de ONOS | Acuerdo 5 | Hilos `m2`, `m2-bd` y `m2-listen` |
| 11 | BD con rol mínimo y TLS | Acuerdo 7 | `onos_m2` (solo SELECT), `sslmode=verify-ca` |
| 12 | Parámetros por network config | Acuerdo 6 | Clave `tel354.m2/m2` (`gen_m2_netcfg.py`) |
| 13 | Log con marca de módulo y evento | Acuerdo 9 | `M2: AuthorizationDenied sesion N [...] -> ip proto P puerto X en dpid/puerto` (lo leen `medir_r3.py` y M6) |
| 14 | Clúster de 3 instancias | LAB6 | Solo la instancia master de cada switch lo programa (y es la que recibe sus Packet-In) |

## Estructura

```
onos-app-m2/
├── pom.xml                     ONOS 2.7.0, Java 11, driver PostgreSQL embebido
├── src/main/java/pe/edu/pucp/tel354/m2/
│   ├── nucleo/                 sin dependencias de ONOS (se prueba con probar_m2.sh)
│   │   ├── Politica.java       matriz en memoria: decidir(rol, usuario, destino, proto, puerto)
│   │   ├── ReglaT1.java        descripción de una regla de la Tabla 1
│   │   └── Tabla1.java         la Tabla 1 deseada (P + 8 reglas)
│   ├── M2Manager.java          ciclo de vida, conciliación, meters, slow-path, eventos
│   ├── BaseDatosM2.java        lectura de la matriz y LISTEN policy_changed
│   ├── Traductor.java          ReglaT1 / DROP 150 / 110 → FlowRule con la cookie de M2
│   ├── M2Config.java           network config
│   └── cli/M2Comando.java      tel354:m2 [-r]
├── src/test/.../Tabla1Prueba.java
├── probar_m2.sh                prueba del núcleo (solo javac)
├── gen_m2_netcfg.py            network config desde el perfil
└── instalar_m2.sh              compila, carga la config, instala y activa
```

## Instalación

Orden: `api → base → m1 → m2 → m3 → m4a → m5`. En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
bash r2-autorizacion/onos-app-m2/instalar_m2.sh --migrar     # la primera vez: retira el puente de la Tabla 1
```

`--migrar` corre `base_auto.py --solo-tabla1 --borrar`: la Tabla 1 del puente (appId `tel354.tabla1`) y la de M2 no pueden convivir, porque OVS trata como la misma regla dos con igual tabla, prioridad y coincidencia. Si el puente sigue en un switch, M2 no toca su Tabla 1 y lo avisa en `tel354:m2`.

## Pruebas

| # | Qué | Cómo | Esperado |
|---|---|---|---|
| M2-1 | Núcleo | `bash r2-autorizacion/onos-app-m2/probar_m2.sh` | `Todo OK` |
| M2-2 | Tabla 1 al detectar el switch | `tel354:m2` y `ovs-ofctl -O OpenFlow13 dump-flows <sw> table=1` | 21/21 en cada switch de acceso (13 permisos + 8 comunes), meters captura y cuarentena |
| M2-3 | R2 sin cambios | `bateria_r2.py` desde cada host | Precisión y alcance del 100 % |
| M2-4 | Denegación | Alumno: `curl -m 3 http://10.200.1.30:5432` | Regla 150 de 8 s en la Tabla 1 y `M2: AuthorizationDenied` en el log |
| M2-5 | policy_changed | `INSERT INTO permiso_rol ...` (como postgres) | `M2: policy_changed ...` y `+1 -0` en cada switch, sin correr scripts |
| M2-6 | Switch nuevo | Fila en `switch_acceso` y conectar el switch | M7 pone su régimen y M2 su Tabla 1 (antes quedaba sin ella) |
| M2-7 | Cierre de sesión | `logout.sh` con un DROP vigente | `M2: sesión N cerrada: k reglas de M2 retiradas` |
| M2-8 | Reinicio | `bundle:restart` de tel354.m2 | 0 Flow-Mods (`+0 -0`): reutiliza reglas y meters |

## Análisis cuantitativo

Con $P$ filas en `v_permiso_efectivo` y $S_a$ switches de acceso: $R_{T1} = (P + 8)\,S_a$ y 2 meters por switch, con $\partial R_{T1}/\partial U = 0$. Las reglas que dependen de los usuarios son temporales: a lo sumo un DROP 150 por cuádrupla cada 8 s, acotado por el meter captura (50 pps por switch).

## Límites conocidos

- La compilación de este repositorio se verificó contra el código fuente de ONOS 2.7.0 (`javac`), no con Maven: falta correr `mvn clean install` en el controlador y las pruebas M2-2 a M2-8 en el slice.
- Las 110 que M1 instala al activar la sesión no se retiran si después se anula la inscripción: dura hasta el fin de la sesión (dueño: M1).
