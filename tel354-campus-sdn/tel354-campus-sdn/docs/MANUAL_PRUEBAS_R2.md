# Manual de pruebas de R2: acceso a recursos privilegiados

**TEL354 · PUCP 2026-2 · Grupo 01 · Versión 1 · 26/10/2026**

R2 exige restringir el acceso a los recursos privilegiados solo a los usuarios autorizados. En la solución, cada rol alcanza solo los recursos de su matriz (reglas 100) y los servidores de los cursos en que está inscrito (reglas 110 por sesión); todo lo demás cae en la captura 55 y se descarta.

Este manual mide la precisión y el alcance de esa restricción y comprueba el modelo de flow entries del AVZ08. Hoy la política proactiva la instala `onos_static.py`; cuando exista la app M2 (slow-path con DROP 150 y recálculo de la política), este manual se ampliará.

**Cómo leer los comandos.** Antes de cada bloque se indica dónde ejecutarlo. El bloque contiene solo comandos, sin `sudo`.

---

## 0. Requisitos previos

- Manual general, secciones 3 a 7 y 9.1: servicios, topología, `onos_static.py` y M1 activos.
- Sesiones abiertas con `entrar.sh`: alumno.demo en h1, docente.demo en h2 y ti.demo en h3 (`MANUAL_PRUEBAS_R1.md`, sección 3).

| Recurso | Destino | ALUMNO | DOCENTE | TI | INVITADO |
|---|---|---|---|---|---|
| Campus Virtual (HTTPS) | 10.200.1.10:443 | Sí | Sí | Sí | No |
| Campus Virtual (SSH) | 10.200.1.10:22 | No | No | Sí | No |
| Biblioteca (HTTPS) | 10.200.1.20:443 | Sí | Sí | No | No |
| Lab SDN TEL354 (ONOS GUI) | 10.200.2.10:8181 | Por curso | Por curso | No | No |
| Lab SDN TEL354 (SSH) | 10.200.2.10:22 | No | No | Sí | No |
| Nube TEL141 (Horizon) | 10.200.2.20:443 | Por curso | Por curso | No | No |
| BD académica | 10.200.3.10:5432 | No | Sí | No | No |
| BD administrativa | 10.200.3.20:5432 | No | No | No | No |
| Gestión de la VM de servicios | 10.200.0.5:22 | No | No | Sí | No |

*Tabla R2.0. Recursos de la demo y acceso esperado. «Por curso»: solo si el usuario está inscrito (alumno.demo y docente.demo lo están en TEL354, no en TEL141).*

---

## 1. Batería de acceso por usuario

Los hosts no leen la base de datos, así que primero se exporta la matriz efectiva. En `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
python3 r2-autorizacion/tests/export_matriz.py > /tmp/matriz.json
```

Luego, en la ventana de cada host:

En `[h1] /home/mininet/tel354-campus-sdn#` ejecutar:
```bash
python3 r2-autorizacion/tests/bateria_r2.py --usuario alumno.demo
```

En `[h2] /home/mininet/tel354-campus-sdn#` ejecutar:
```bash
python3 r2-autorizacion/tests/bateria_r2.py --usuario docente.demo
```

En `[h3] /home/mininet/tel354-campus-sdn#` ejecutar:
```bash
python3 r2-autorizacion/tests/bateria_r2.py --usuario ti.demo
```

Cada batería intenta 5 conexiones a cada uno de los 9 recursos y compara con lo esperado para el usuario. `--usuario` solo indica qué resultado se espera; deciden las reglas de la sesión real.

| Usuario | Autorizados | Precisión (no autorizados bloqueados) | Alcance (autorizados que conectan) |
|---|---|---|---|
| alumno.demo | 3 de 9 | 30/30 | 15/15 |
| docente.demo | 4 de 9 | 25/25 | 20/20 |
| ti.demo | 4 de 9 | 25/25 | 20/20 |
| invitado (temporal) | 0 de 9 | 45/45 | — |

*Tabla R2.1. Referencia del 19/10: 125/125 bloqueados y 55/55 conectados.*

Comprobaciones adicionales:

- alumno.demo y docente.demo alcanzan el Lab SDN TEL354 y **no** la Nube TEL141;
- ti.demo no alcanza ninguno de los dos;
- un host sin sesión no alcanza nada (`MANUAL_PRUEBAS_R1.md`, sección 2);
- sancion.demo (VLAN 999) no alcanza ningún recurso interno: las reglas 58 y 57 lo aíslan sin Packet-In.

---

## 2. Flow entries frente al modelo del AVZ08

Con h1 y h2 en as1 y h3 en as2, en `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
python3 r2-autorizacion/tests/contar_flows.py
```

El modelo del switch de acceso en la maqueta es

$$F_{acc} = 28 + P + 5S + C$$

donde $P = 2$ son los puertos de host, $S$ las sesiones activas del switch y $C$ las reglas 110 de sus cursos.

| Switch | Sesiones | Esperado | Desglose (T0 + T1 + T2) |
|---|---|---|---|
| as1 | $S=2$, $C=2$ | 42 | 13 + 23 + 6 |
| as2 | $S=1$, $C=0$ | 35 | 10 + 21 + 4 |
| core | — | 31 | 5 + 7·2 + 12, constante en el número de usuarios |

*Tabla R2.2. Flow entries esperadas.*

Cada sesión añade 5 entradas a su switch de acceso (3 en la Tabla 0 y 2 en la Tabla 2), más una regla 110 por servidor de sus cursos; las reglas de R2 por rol y las del core no crecen con los usuarios. **Referencia (19/10):** coincidencia exacta en cuatro instantáneas (42/42, 36/36, 35/35, 30/30 y core 31/31).

Para ver el detalle de un switch, en `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
ovs-ofctl -O OpenFlow13 --names --rsort=priority dump-flows as1 table=1
```

---

## 3. Captura de los accesos denegados

Un acceso no autorizado debe caer en la regla 55 (captura con meter hacia el controlador). Desde h1, intentar un recurso no autorizado: en `[h1] /home/mininet/tel354-campus-sdn#` ejecutar:
```bash
nc -zv -w2 10.200.3.10 5432
```

En `root@mininet-vm:/home/mininet/tel354-campus-sdn#` ejecutar:
```bash
ovs-ofctl -O OpenFlow13 --names dump-flows as1 table=1 | grep "priority=55"
```

Esperado: la conexión falla y el contador `n_packets` de la regla 55 sube. Hoy esos Packet-In llegan a ONOS y nadie los atiende (se pierden); cuando exista M2, cada intento instalará un DROP 150 de 8 s y publicará AuthorizationDenied.

---

## 4. Resultados

| Métrica | Prueba | Referencia (19/10) | Obtenido |
|---|---|---|---|
| Precisión | Sección 1 | 125/125 = 100 % | |
| Alcance | Sección 1 | 55/55 = 100 % | |
| Acceso por curso | Sección 1 | TEL354 sí, TEL141 no | |
| Flow entries as1 / as2 / core | Sección 2 | 42 / 35 / 31 | |
| Captura de denegados | Sección 3 | Regla 55 con contador en aumento | |

*Tabla R2.3. Registro de resultados de R2.*

---

## 5. Problemas frecuentes de R2

| Síntoma | Causa | Acción |
|---|---|---|
| `bateria_r2.py`: FileNotFoundError `/tmp/matriz.json` | Falta la exportación | `export_matriz.py > /tmp/matriz.json` en la ventana raíz |
| `bateria_r2.py` con alcance 0 % | Se ejecutó fuera del host o la sesión expiró | Comprobar el prompt `[h1]` e `ip -br addr`; repetir el login |
| El conteo de flows no coincide | Sesiones distintas de las supuestas, o una regla 40000 de ARP | Revisar la tabla `sesion`; `requestInterceptsEnabled false` (manual general, Tabla G.11) |
| Un usuario alcanza un servidor de curso sin estar inscrito | Inscripción en el ciclo vigente o regla 110 vieja | Revisar `inscripcion` y `ciclo`; cerrar y abrir la sesión |
| Reglas 100 desactualizadas tras cambiar la política | Hoy la política se instala solo al correr `onos_static.py` | Volver a ejecutar `onos_static.py` (lo resolverá M2 con `policy_changed`) |

*Tabla R2.4. Síntomas, causas y acciones.*
