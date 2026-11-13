# tel354.base · M7 (régimen base) + M8 (caminos fijos)

App de ONOS 2.7 que instala el régimen base de cada switch **cuando ONOS lo detecta**, en lugar de `base_auto.py`. El rol del switch sale de la tabla `switch_acceso` (dpid → pabellón): para sumar un pabellón basta con insertar su fila, sin editar el perfil ni correr scripts.

## Qué instala

| Módulo (appId, cookie) | Switch | Reglas |
|---|---|---|
| M7 (`tel354.m7`, 0x01) | Acceso, Tabla 0 | 900 EAPOL → controlador · 800 ARP por puerto de host, con meter `ctl_P` (10 pps, ráfaga 10) · 600 uplink → Tabla 2 · 10 drop |
| M8 (`tel354.m8`, 0x05) | Acceso, Tabla 2 | 200 a 10.200.0.0/16 y 180 por defecto: pop VLAN → uplink |
| M8 (`tel354.m8`, 0x05) | Core | Por pabellón N: 210 (VLAN 100+N y VLAN 999), 205 (push 999), 200 × 6 roles (push 100+N) · 200 a servidores (MAC del controlador) · 0 drop |

La **Tabla 1** (permisos 100 y las comunes 60, 58, 57, 55, 50, 0, con los meters `captura` y `cuarentena`) es de **M2** (LLSD R3, Tabla 7): la instala la app `tel354.m2` al detectar cada switch de acceso (el que tiene la regla 600 de M7). El puente `base_auto.py --solo-tabla1` (appId `tel354.tabla1`) queda para diagnóstico. El **proxy ARP** del gateway sigue en M1, porque depende del estado de sesión.

Cookie: `appId | tipo | id | índice`, con id = prioridad·2¹⁶ + grupo (grupo = puerto en la 800, pabellón en el core y 0 en el resto). Por ejemplo, la 800 del puerto 3 tiene id `0x03200003`.

## Cómo funciona

1. **Disparadores.** `DeviceListener` (switch conectado, puertos), `LinkListener` (LLDP/BDDP) y la network config. Como respaldo, cada 30 s se relee `switch_acceso` y se concilia todo.
2. **Conciliación idempotente.** Para cada switch se calcula el conjunto deseado (`nucleo/Regimen`), se compara con las reglas de M7 y M8 que ya están en el almacén de ONOS y solo se envía la diferencia, en dos etapas (retirar y luego agregar). El almacén guarda la regla tal como se envió (las estadísticas solo actualizan contadores y estado), así que la comparación es exacta; el meter se compara aparte, porque `DefaultTrafficTreatment.equals` no lo incluye. Si el switch se reconecta o la app se reinicia, la diferencia es vacía y no sale ningún Flow-Mod: ONOS reinstala por su cuenta lo que falte en el switch.
3. **Puertos.** El uplink es el puerto con enlace hacia el core; los puertos de host son los físicos restantes sin enlace. Si el descubrimiento pierde un enlace por un momento (ONOS lo da de baja a los 10 s sin sondeos, algo posible con BDDP en el slice), se conserva el último conocido: así no se retiran la 600 ni la Tabla 2.
4. **Inventario.** `switch_acceso` se relee cada 30 s y, además, apenas aparece un switch nuevo: su régimen no espera la siguiente lectura.
5. **Meters asíncronos.** Cada meter `ctl_P` se pide con `MeterService`, y la regla 800 de su puerto sale 1 s después (el Meter-Mod viaja antes por el mismo canal OpenFlow). M1 sigue tomando el meter de la regla 800 para sus reglas 525 y 505.
6. **Hilos y clúster.** Todo corre en un hilo propio, nunca en el de eventos de ONOS. Solo programa la instancia master de cada switch.

## Robustez

Una regla solo se retira con información positiva: el puerto ya no existe, el switch salió de `switch_acceso` o cambió su pabellón. Si falta información de topología (LLDP/BDDP aún no descubre un enlace, o lo pierde un momento), se usa lo último conocido; tras un reinicio de la app, lo último conocido se recupera de sus propias reglas en el almacén de ONOS. El puerto del core hacia los servidores se fija en el perfil (`PUERTO_SERVIDORES`): descubrirlo como «el único puerto libre» falla si un pabellón está apagado al arrancar.

`banco/banco.sh` ejecuta la app real contra un ONOS y unos switches simulados (dobles de la API de ONOS 2.7.0 en `banco/src`) y recorre 15 escenarios con 44 verificaciones: arranque antes del descubrimiento, reinicio de la app con y sin enlaces, pérdida de un enlace, pabellón nuevo, reasignación por la BD, switch sin rol, puertos que aparecen y desaparecen, caída de PostgreSQL, reinicio de un switch, meter borrado por fuera, baja de un switch y desactivación. No reemplaza a la compilación con Maven ni a las pruebas en el slice.

## Validación en el slice (12/11/2026)

Pasan B0 a B6, B9 y B12 (`docs/VALIDACION.md`, sección 7 ter). Datos medidos: 7 + 7 + 20 reglas y 2 + 2 meters en el slice; 0 Flow-Mods al reiniciar la app, sin cortar el tráfico (60/60 respuestas); un pabellón nuevo con solo su fila, con el core de 20 a 29 reglas y $t_{\text{conv}} = 7{,}2\,\mathrm{s}$ desde la conexión (presupuesto $\le 9{,}3\,\mathrm{s}$); reasignación por la BD en $\le 30\,\mathrm{s}$; y convivencia con la contención de M5 y los contadores de M3 sin tocar su meter.

## Instalación

Orden: `api → base → m1 → m3 → m5`. M1 declara `tel354.base` en `onos.app.requires` (sin la regla 900 no le llega EAPOL), así que ONOS impone el orden: no instala M1 sin base, y desactivar o desinstalar base desactiva o desinstala antes a M1 y a M5. Esto importa porque, al retirarse base, se van sus meters `ctl_P`, y OVS borra con ellos las reglas 525 y 505 de M1.

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
export TEL354_ENV=una_instancia.env
bash comun/onos-app-base/instalar_base.sh --migrar
bash r2-autorizacion/onos-app-m2/instalar_m2.sh --migrar     # Tabla 1 (retira el puente si estaba)
```

`--migrar` retira, una sola vez, lo que dejó `base_auto.py` (reglas `tel354.base` y meters de REST). Consola: `tel354:base` (estado por switch) y `tel354:base -r` (reglas deseadas).

## Análisis cuantitativo

Sea $H_s$ el número de puertos de host del switch de acceso $s$, $S_a$ el de switches de acceso (pabellones), $D$ el de destinos fijos del core y $U$ el de usuarios.

$$R_{\text{acc}}(s) = \underbrace{H_s + 3}_{\text{M7}} + \underbrace{2}_{\text{M8}} = H_s + 5, \qquad M_{\text{acc}}(s) = H_s$$

$$R_{\text{core}} = 9\,S_a + D + 1$$

$$R_{\text{base}} = \sum_{s} (H_s + 5) + 9\,S_a + D + 1 = \sum_s H_s + 14\,S_a + D + 1 \in \Theta(S_a \bar H), \qquad \frac{\partial R_{\text{base}}}{\partial U} = 0$$

| Escenario | Acceso | Core | Total |
|---|---|---|---|
| Slice ($H = 2$, $S_a = 2$, $D = 1$) | 7 + 7 | 20 | 34 reglas y 4 meters |
| Pabellón con 48 puertos | 53 (2,7 % de 2 000) | — | — |
| Plan completo ($S_a = 15$) | — | 137 | — |

Con la Tabla 1 de M2 (13 permisos y 8 comunes en el piloto), cada switch de acceso vuelve a sumar 28 reglas, la misma cifra de `base_auto.py`.

- **Esfuerzo operativo.** Antes, sumar un pabellón exigía editar el perfil y volver a correr `base_auto.py`, que reenvía el régimen de los $S_a$ switches: $O(S_a)$ Flow-Mods y pasos manuales. Ahora basta con una fila en `switch_acceso`: $O(1)$ pasos y $H + 5 + 9$ Flow-Mods (el switch nuevo más su tramo en el core), independientes de $S_a$.
- **Costo en el controlador.** Cada conciliación de un switch lee su almacén local: $O(R_s + M_s)$, sin mensajes OpenFlow si no hay diferencia. La conciliación periódica cuesta una consulta SQL de $S_a$ filas y $O\!\left(\sum_s R_s\right)$ lecturas locales cada $T_c = 30\,\mathrm{s}$.
- **Tiempo de convergencia** de un switch nuevo:
  $$t_{\text{conv}} \approx t_{\text{enlace}} + t_{\text{agrupación}} + t_{\text{meter}} + t_{\text{FM}} + t_{\text{conf}}$$
  con $t_{\text{enlace}} \le 3\,\mathrm{s}$ (`probeRate` de LLDP/BDDP), $t_{\text{agrupación}} = 0{,}3\,\mathrm{s}$, $t_{\text{meter}} = 1\,\mathrm{s}$, $t_{\text{FM}} \approx$ un RTT de OpenFlow, y $t_{\text{conf}} \le 5\,\mathrm{s}$ (ONOS marca ADDED con sus estadísticas de flujos, `pollFrequency` = 5 s). La cota es de unos $9{,}3\,\mathrm{s}$ más los RTT. Los valores son los de ONOS 2.7.0 por defecto. Esperar el ADDED del meter costaría hasta 10 s más, porque ONOS lo confirma con estadísticas de meters cada 10 s; por eso la 800 sale 1 s después del Meter-Mod, que viaja antes por el mismo canal. Si se pierde un evento, la conciliación periódica lo corrige en $\le T_c$. El log registra dos tiempos: el de los Flow-Mods confirmados (barrier) y el de las reglas ADDED.
- **Límite del plan.** Las direcciones 10.(16k+N).0.0/16 dejan $1 \le N \le 15$ pabellones dentro del /12 de cada rol (VLAN 101–115). M8 ignora un pabellón fuera de ese rango. El modelo de datos es más estricto: `pabellon.id` admite de 1 a 13. Manda el modelo ($S_a \le 13$); el plan deja una holgura de 2.

`probar_base.sh` verifica sin ONOS las fórmulas $H + 5$ (para $H = 0..48$) y $9P + D + 1$ (para $P = 1..15$), y que las cookies sean únicas en cada switch.
