# Guía de validación de M1 y M5 en el slice del LAB5 (VNRT)

**TEL354 · PUCP 2026-2 · Grupo 01 · Versión 1.9 · 13/11/2026**

Esta guía valida en el slice del LAB5 que el código de la versión 2.0 (tel354-api, M1 y M5) funciona: primero que compila contra ONOS 2.7 real, luego que las apps se activan en el controlador y, por último, que hacen su trabajo con hosts reales (802.1X, IP por rol, reglas de sesión, contención y cuarentena). Se parte del slice en su estado inicial, después de `slice_reset.yml`.

El slice del LAB5 no es el piloto del LAB6, pero tiene lo necesario para validar M1 y M5: ONOS 2.7, un switch central, dos switches con dos hosts cada uno y una VM que puede hacer de todos los servidores. Lo que no se puede validar aquí es lo propio del clúster y del control in-band (prueba V9 de la Guía de M1).

Desde la versión 1.4 la guía incluye la app `tel354.base` (M7 + M8), que reemplaza a `base_auto.py`: su plan de pruebas (B0 a B12) está en la sección 7 bis. La versión 1.6 protege `ens3` en los scripts del host (paso 11) y agrega las pruebas locales previas al slice (paso 12 bis). La versión 1.7 registra los resultados de la validación de M7 y M8 (sección 7 ter) y corrige los pasos 4, 16, 17 y 24. La versión 1.8 agrega el montaje manual de los túneles VXLAN (paso 11 bis) y las pruebas de M2 (Tabla 1 al detectar cada switch, que reemplaza al puente `base_auto.py --solo-tabla1`) y de M6 (auditoría en `auth_log`), en la sección 7 quater. La versión 1.9 agrega M4a y el IDS (Suricata), en la sección 7 sexies.

**Capturas.** Cada paso indica qué captura tomar (F01 a F61) para el informe de validación. Que se vean el prompt y el comando.

**Versión del código.** La guía no depende de una versión concreta del zip: el paso 1 usa `tel354-campus-sdn-vX.zip` y deja la carpeta anterior como `tel354-campus-sdn.anterior`, para que no queden archivos de versiones previas mezclados con los nuevos.

**Cómo leer los comandos.** Antes de cada bloque se indica la VM y el prompt. Los bloques contienen solo comandos y van sin `sudo`: en cada VM se entra con `ubuntu`/`ubuntu` y se pasa a root con `sudo -i`. En la consola de ONOS el prompt es `onos@root >`.

## 1. Correspondencia con el piloto

| LAB6 (piloto) | LAB5 (validación) | Qué hace en la validación |
|---|---|---|
| Controlador (3 instancias) | controller (192.168.0.10): 1 instancia de ONOS 2.7 en Docker | M1, M5 y la API de TI |
| S-SERVICIOS, S-IDENTIDAD, S-RECURSOS, S-CURSOS | controller, en su interfaz de datos hacia sw1 | Las mismas IP 10.200.x.y: Kea, DNS, PostgreSQL, OpenLDAP, FreeRADIUS y recursos de prueba |
| SW-C1 y SW-C2 | sw1 (of:00000000000000c1) | Core: retorno a usuarios por VLAN y entrega a los servidores |
| SW-A1 (pabellón 1) | sw2 (of:0000000000000001) | Switch de acceso de h1 y h2 |
| SW-A2 (pabellón 2) | sw3 (of:0000000000000002) | Switch de acceso de h3 y h4 |
| Ubuntu-1, Kali-1 | h1 (alumno01), h2 (alumno02) | Usuario y atacante del pabellón 1 |
| Ubuntu-2, Kali-2 | h3 (docente01), h4 (jp01) | Usuarios del pabellón 2 (h4 hace de atacante en M5) |
| Control in-band (172.16.0.0/16) | Control fuera de banda por 192.168.0.0/24 (ens3) | No se necesitan reglas para el plano de control |

*Tabla 1. Cómo se reparte el piloto en el slice del LAB5.*

Los scripts no conocen el laboratorio: todo lo que cambia frente al piloto está en el perfil `comun/config/una_instancia.env`, que fija las IP, los datapath-id y la interfaz de datos. Los scripts de servicios, instalación y régimen base lo toman con `TEL354_ENV=una_instancia.env`; con `slice.env` (por defecto) sirven al piloto.

| Nivel | Qué valida | Pasos | Tiempo aproximado |
|---|---|---|---|
| 1 | Compilación real contra ONOS 2.7 | 1 a 3 | 15 min |
| 2 | Activación de las apps en ONOS (OSGi, comandos, REST, driver JDBC) | 4 y 5 | 10 min |
| 3 | Comportamiento con hosts reales | 6 a 14 | 1 h 30 min |

*Tabla 2. Niveles de la validación.*

## 2. Preparación

### Paso 1. Repositorio en controller

`tel354-campus-sdn-vX.zip` es el zip de la versión que se va a validar: reemplazar `vX` por la del archivo recibido (por ejemplo, `v5`).

En la terminal de tu equipo ejecutar (5800 es el puerto de controller en el gateway del slice; `<IP_GATEWAY>` es la del LAB5):

```bash
scp -P 5800 tel354-campus-sdn-vX.zip ubuntu@<IP_GATEWAY>:
ssh -p 5800 ubuntu@<IP_GATEWAY>
```

En `ubuntu@controller:~$` ejecutar:

```bash
sudo -i
```

En `root@controller:~#` ejecutar:

```bash
mv /home/ubuntu/tel354-campus-sdn-vX.zip /root/ && cd /root
rm -rf tel354-campus-sdn.anterior; [ -d tel354-campus-sdn ] && mv tel354-campus-sdn tel354-campus-sdn.anterior
unzip -q tel354-campus-sdn-vX.zip
cd tel354-campus-sdn && ls
grep -E "sw[123]" /root/TEL354_LAB5_2026_2/ansible/inventory.ini
```

La última línea muestra las IP de gestión de los switches: sw1 192.168.0.11, sw2 192.168.0.12 y sw3 192.168.0.13. El inventario del LAB5 está en `/root/TEL354_LAB5_2026_2/ansible` y no lista los hosts: su IP de gestión es la de `ens3` (`ip -br addr` en cada uno): h1 192.168.0.21, h2 .22, h3 .23 y h4 .24.

### Paso 2. Paquetes de controller

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
apt update
DEBIAN_FRONTEND=noninteractive apt install -y openjdk-11-jdk maven jq unzip python3-requests python3-psycopg2 \
  postgresql postgresql-client slapd ldap-utils freeradius freeradius-ldap freeradius-postgresql \
  kea-dhcp4-server kea-ctrl-agent kea-admin bind9 openssl
systemctl stop freeradius kea-dhcp4-server kea-ctrl-agent 2>/dev/null; true
```

## 3. Nivel 1: compilación

### Paso 3. Compilar tel354-api, M1 y M5

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
for d in comun/tel354-api r1-identidad/onos-app-m1 r3-ataques-encubiertos/onos-app-m5; do
  (cd $d && mvn -q -U clean install -DskipTests -Dcheckstyle.skip) && echo "OK $d" || echo "FALLA $d"
done
ls comun/tel354-api/target/*.oar r1-identidad/onos-app-m1/target/*.oar r3-ataques-encubiertos/onos-app-m5/target/*.oar
bash r3-ataques-encubiertos/onos-app-m5/probar_evaluador.sh | tail -1
```

Resultado esperado: tres `OK`, tres archivos `.oar` y `Todas las pruebas pasaron`. La primera compilación descarga las dependencias de ONOS 2.7.0 (unos minutos). Los avisos `illegal reflective access` de Error Prone son propios de Java 11 y no afectan.

`-Dcheckstyle.skip` es necesario: el pom padre de ONOS 2.7 ejecuta checkstyle, que exige javadoc completo en cada método y detiene la compilación de tel354-api. `-U` hace que Maven reintente las dependencias que falló en un intento anterior.

**Captura F01:** la salida completa del bloque.

**Si algo falla:** guardar la salida completa del módulo que falló (`cd <módulo> && mvn clean install -DskipTests > /root/mvn.log 2>&1`) y enviarla; es la información que hace falta para corregirlo.

## 4. Nivel 2: activación en ONOS

### Paso 4. ONOS 2.7 en Docker

Se usa la imagen oficial `onosproject/onos:2.7.0`, que ya está en controller, solo con `drivers`, `openflow` y `gui2`. Las apps `fwd` y `proxyarp` no deben estar activas: responderían el ARP y reenviarían tráfico por su cuenta. La carpeta de certificados se monta desde ya; el `ca.pem` se crea en el Paso 8.

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
docker rm -f onos 2>/dev/null; mkdir -p /etc/tel354/certs
docker run -d --name onos --network host --restart unless-stopped \
  -e ONOS_APPS=drivers,openflow,gui2 -v /etc/tel354/certs:/etc/tel354/certs:ro onosproject/onos:2.7.0
until curl -sf -u onos:rocks http://127.0.0.1:8181/onos/v1/applications >/dev/null; do sleep 5; done; echo "ONOS listo"
echo "alias onos-cli='ssh -p 8101 -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o StrictHostKeyChecking=no -o HostKeyAlgorithms=+ssh-rsa -o PubkeyAcceptedAlgorithms=+ssh-rsa onos@localhost'" >> ~/.bashrc && source ~/.bashrc
```

Con Docker no viene el alias `onos-cli`: la consola de ONOS se abre por SSH al puerto 8101 (usuario `onos`, clave `rocks`), y la última línea crea el alias. El alias no guarda la clave del host: cada contenedor nuevo genera otra, y con una guardada `ssh` rechazaría la conexión («REMOTE HOST IDENTIFICATION HAS CHANGED»).

**Captura F02:** la línea `ONOS listo`.

### Paso 5. Instalar las tres apps y comprobarlas

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
for oar in comun/tel354-api/target/tel354-api-2.0.0.oar r1-identidad/onos-app-m1/target/tel354-m1-2.0.0.oar \
           r3-ataques-encubiertos/onos-app-m5/target/tel354-m5-2.0.0.oar; do
  curl -s -u onos:rocks -X POST -H "Content-Type: application/octet-stream" \
       "http://127.0.0.1:8181/onos/v1/applications?activate=true" --data-binary @$oar | jq -r '.name + "  " + .state'
done
sleep 10; curl -s -u onos:rocks http://127.0.0.1:8181/onos/campus/v1/r3/modo; echo
onos-cli
```

En `onos@root >` (clave `rocks`) ejecutar:

```
apps -a -s | grep tel354
scr:list | grep -i tel354
tel354:sesiones
tel354:r3-modo
```

El recurso web de M5 se publica unos segundos después de que la app queda `ACTIVE`: sin el `sleep 10`, el `curl` responde 404. Si se vuelve a ejecutar el bucle, las líneas salen en blanco porque las apps ya están instaladas.

El `grep` de la consola de Karaf no acepta `-E` ni filtra bien patrones alternativos: los logs se filtran desde controller. Después de `logout`, en `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
docker logs onos 2>&1 | grep -E "M1|M5|ERROR|Exception" | tail -20
```

**Capturas:** F03, la salida del bloque en controller; F04, `apps -a -s`, `scr:list` y `tel354:sesiones` en la consola; F05, `tel354:r3-modo` y el `docker logs` filtrado.

| Comprobación | Resultado esperado |
|---|---|
| `curl … /applications` | `tel354.api ACTIVE`, `tel354.m1 ACTIVE`, `tel354.m5 ACTIVE` |
| `curl … /onos/campus/v1/r3/modo` | `{"modo":"ACTIVO"}`: el recurso web de M5 responde |
| `scr:list` | M1Manager, M5Manager y ApiDocRegistrator en `enabled, 1 instance` (así lo muestra Karaf 4.2), ninguno `UNSATISFIED` |
| `tel354:sesiones`, `tel354:r3-modo` | Responden (los comandos de Karaf están registrados) |
| `docker logs` | `M1: núcleo no iniciado en 172.17.0.1 … se reintenta en 10 s` y `M5: mantenimiento sin BD`: **correcto en este paso**, todavía no hay base de datos ni network config |
| Log | Sin `ClassNotFoundException`, `NoSuchMethodError` ni `Unresolved constraint` |

*Tabla 3. Criterios del nivel 2.*

Con esto queda validado el nivel 2. Salir de la consola con `logout`.

## 5. Nivel 3: comportamiento con hosts reales

### Paso 6. Red de datos de controller

En `root@controller:~/tel354-campus-sdn#` ejecutar para identificar la interfaz de datos (la que no es ens3 ni las de Docker) y darle las IP de los servidores:

```bash
export TEL354_ENV=una_instancia.env
ip -br link | grep -vE "lo|docker|veth|ens3"
bash comun/topo/servicios_coubicados.sh
```

El script toma la interfaz `IF_DATOS=ens4` del perfil. Si la interfaz de datos tiene otro nombre, pasarlo como argumento y cambiar `IF_DATOS` en `comun/config/una_instancia.env`. `TEL354_ENV` queda exportado para los Pasos 8 a 10 en esta terminal.

**Captura F06:** las 13 IP 10.200.x.y en `ens4` y la línea con su MAC.

### Paso 7. Switches: control y datapath-id

`bootstrap.yml` del LAB5 deja cada OVS con `fail_mode=secure`, OpenFlow 1.3 y el controlador en 192.168.0.10:6653. Después se fija el datapath-id de cada switch para que coincida con la semilla de la base de datos (`switch_acceso`).

En `root@controller:~#` ejecutar (pide la contraseña de sudo de los switches: `ubuntu`):

```bash
cd /root/TEL354_LAB5_2026_2/ansible
ansible-playbook bootstrap.yml
ansible sw1 -b -K -m command -a "ovs-vsctl set bridge sw1 other-config:datapath-id=00000000000000c1"
ansible sw2 -b -K -m command -a "ovs-vsctl set bridge sw2 other-config:datapath-id=0000000000000001"
ansible sw3 -b -K -m command -a "ovs-vsctl set bridge sw3 other-config:datapath-id=0000000000000002"
cd /root/tel354-campus-sdn
```

Cada `BECOME password:` pide la contraseña de sudo de los switches (`ubuntu`). Ansible debe ejecutarse desde esa carpeta: ahí están su configuración y el inventario.

En `onos@root >` ejecutar:

```
devices
links
```

Resultado esperado: los tres switches con los id `of:00000000000000c1`, `of:0000000000000001` y `of:0000000000000002`, y cuatro enlaces (sw1↔sw2 y sw1↔sw3, en ambos sentidos). En el slice: sw2 puerto 1 ↔ sw1 puerto 2 y sw3 puerto 3 ↔ sw1 puerto 3.

Los mismos switches aparecen además con su datapath-id anterior y `available=false`. Se retiran con `device-remove <id>` (uno por cada id anterior) y se repite `devices`.

Los enlaces salen como `INDIRECT`: ONOS los descubrió por BDDP (difusión) y no por LLDP, porque la red virtual del slice no reenvía las tramas a las direcciones de grupo reservadas 01:80:c2:00:00:0x. Es un riesgo para el login 802.1X, cuyo EAPOL usa 01:80:c2:00:00:03: se comprueba en V2.

**Captura F07:** `devices` (ya limpio) y `links`.

### Paso 8. Servicios en controller

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
export TEL354_ENV=una_instancia.env
bash scripts/gen_certs.sh
python3 comun/db/gen_usuarios.py
bash comun/db/setup_db.sh
bash r1-identidad/servicios/ldap/setup_ldap.sh
bash r1-identidad/servicios/radius/setup_radius.sh
python3 r1-identidad/servicios/kea/gen_kea_conf.py --iface ens4 --extra lo --relay 192.168.0.10 > /etc/kea/kea-dhcp4.conf
cp r1-identidad/servicios/kea/kea-ctrl-agent.conf /etc/kea/kea-ctrl-agent.conf
echo -n "kea-demo" > /etc/kea/kea-api-password
chown root:_kea /etc/kea/kea-api-password && chmod 640 /etc/kea/kea-api-password
kea-dhcp4 -t /etc/kea/kea-dhcp4.conf && systemctl restart freeradius kea-dhcp4-server kea-ctrl-agent
systemd-run --unit tel354-recursos -p WorkingDirectory=$PWD python3 -u scripts/servicios/recursos.py --json comun/config/recursos_slice.json
```

En Ubuntu 24.04, `kea-ctrl-agent` no arranca (queda `inactive`, con `ConditionFileNotEmpty` sin cumplir) si no existe `/etc/kea/kea-api-password`, aunque la configuración no use autenticación. FreeRADIUS se reinicia al final porque necesita la base `campus` para arrancar.

`setup_db.sh` lee cada archivo SQL como root y se lo pasa a `psql` por la entrada estándar: el usuario `postgres` no puede entrar a `/root`, donde está el repositorio.

En `root@controller:~/tel354-campus-sdn#` ejecutar para el DNS del campus:

```bash
cat > /etc/bind/named.conf.options <<'EOF'
options {
  directory "/var/cache/bind";
  listen-on { 10.200.0.14; 127.0.0.1; };
  listen-on-v6 { none; };
  allow-query { 10.0.0.0/8; 127.0.0.1; };
  recursion yes;
};
EOF
cat > /etc/bind/named.conf.local <<'EOF'
zone "campus.pucp.edu.pe" { type master; file "/etc/bind/db.campus"; };
EOF
cat > /etc/bind/db.campus <<'EOF'
$TTL 300
@ IN SOA ns.campus.pucp.edu.pe. ti.campus.pucp.edu.pe. (1 300 60 600 300)
@ IN NS ns.campus.pucp.edu.pe.
ns         IN A 10.200.0.14
paideia    IN A 10.200.1.10
biblioteca IN A 10.200.1.20
labsdn     IN A 10.200.2.10
EOF
named-checkconf && systemctl restart named
```

En `root@controller:~/tel354-campus-sdn#` ejecutar para comprobar los servicios:

```bash
systemctl is-active postgresql slapd freeradius kea-dhcp4-server kea-ctrl-agent named tel354-recursos | paste -d' ' <(printf "%s\n" postgresql slapd freeradius kea-dhcp4 kea-ctrl-agent named recursos) -
su postgres -c "psql -d campus -Atc \"SELECT split_part(campus_rol_efectivo(uid_ldap),'|',1), count(*) FROM usuario GROUP BY 1 ORDER BY 1\""
ss -lnup | grep -E ":1812|:67 "
curl -s -X POST -H "Content-Type: application/json" -d '{"command":"version-get","service":["dhcp4"]}' http://10.200.0.13:8000/ | jq -r '.[0].result'
dig +short @10.200.0.14 paideia.campus.pucp.edu.pe
```

Resultado esperado: los siete servicios `active`; 20 cuentas o más por rol (REJECT 1 es `inactivo.demo`, desactivada a propósito); FreeRADIUS en `0.0.0.0:1812`; Kea en `10.200.0.13:67` y `127.0.0.1:67`; `0` del Control Agent y `10.200.1.10` del DNS.

**Captura F08:** la salida completa del bloque.

Kea escucha también en `lo` (`--extra lo`): el relay de M1 está en esta misma VM, así que sus mensajes entran a Kea por loopback y no por ens4.

### Paso 9. Régimen base

`base_auto.py` descubre en ONOS por qué puerto se une cada switch (LLDP) y qué puertos quedan para hosts y para controller, e instala las reglas de sw1 (core) y las Tablas 0, 1 y 2 de sw2 y sw3, con sus meters.

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
python3 r2-autorizacion/proactivo/base_auto.py --dry-run
python3 r2-autorizacion/proactivo/base_auto.py
```

Resultado esperado: `core: pabellón 1 por 2, pabellón 2 por 3, controlador (<MAC de ens4>) por 1`, 20 reglas en el core y 28 en cada switch de acceso. En el slice, sw2 tiene el uplink en el puerto 1 y los hosts en 2 y 3; sw3, el uplink en el 3 y los hosts en 1 y 2.

**Captura F09:** la salida de los dos comandos.

> Desde la versión 1.4, el régimen base lo instala la app `tel354.base` (M7 + M8) y `base_auto.py` queda para diagnóstico. La migración y sus pruebas están en la sección 7 bis.

### Paso 10. Configuración de M1 y M5

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
python3 r1-identidad/onos-app-m1/gen_m1_netcfg.py > /tmp/m1-netcfg.json
curl -s -u onos:rocks -X POST -H "Content-Type: application/json" http://127.0.0.1:8181/onos/v1/network/configuration -d @/tmp/m1-netcfg.json
curl -s -u onos:rocks -X POST -H "Content-Type: application/json" http://127.0.0.1:8181/onos/v1/network/configuration -d @r3-ataques-encubiertos/config/r3-netcfg.json
sleep 15; docker logs onos 2>&1 | grep -E "M1:|M5:" | tail -5
```

`gen_m1_netcfg.py` arma la network config de M1 a partir del perfil (`TEL354_ENV`): con `una_instancia.env` usa `nasIp` = 192.168.0.10; en el clúster usaría `nasIpPorNodo`.

Resultado esperado: `M1: autenticador 802.1X listo (NAS /192.168.0.10:… -> RADIUS 10.200.3.10:1812)`, `M1: relay DHCP listo (/192.168.0.10:67 -> Kea 10.200.0.13:67)` y `M5: modo ACTIVO, reserva 96 por switch, N1 900 s, N2 3600 s`. Los avisos del Paso 5 desaparecen. El `172.17.0.1` de la línea de M1 es el identificador de la instancia de ONOS.

**Captura F10:** la salida del `docker logs`.

### Paso 11. Hosts

En `root@controller:~#` ejecutar (la contraseña es `ubuntu`, una vez por host):

```bash
for ip in 192.168.0.21 192.168.0.22 192.168.0.23 192.168.0.24; do
  scp -rq -o StrictHostKeyChecking=accept-new /root/tel354-campus-sdn /etc/tel354/certs/ca.pem ubuntu@$ip:
done
```

A cada host se entra desde controller con `ssh ubuntu@192.168.0.2X`.

En `ubuntu@h1:~$` (y igual en h2, h3 y h4) ejecutar:

```bash
sudo -i
```

En `root@h1:~#` ejecutar:

```bash
mv /home/ubuntu/tel354-campus-sdn /root/ && mkdir -p /etc/tel354/certs && mv /home/ubuntu/ca.pem /etc/tel354/certs/
DEBIAN_FRONTEND=noninteractive apt install -y wpasupplicant isc-dhcp-client nmap hping3 dsniff dnsutils netcat-openbsd
ip -br addr
```

En h2 y h4 se agrega `python3-scapy` a ese `apt install` (pruebas de tráfico real de M5). La interfaz de datos es `ens4` (la que tenía 10.0.0.x en el LAB5); `ens3` es la de gestión y no se toca. En `root@h1:~#` ejecutar:

```bash
ip addr flush dev ens4 && ip link set ens4 up
cd /root/tel354-campus-sdn
ls /etc/tel354/certs/ca.pem && ip -br addr show ens4
```

Resultado esperado: el `ca.pem` y `ens4` en UP, sin IPv4.

**ens3 no se toca.** `ens3` es la interfaz de gestión de cada VM (SSH) y ningún script del proyecto la limpia, la configura ni hace 802.1X o DHCP sobre ella. `login.sh`, `logout.sh` y `wpa_accion.sh` tienen cinco barreras: no la eligen por defecto, la rechazan si se indica, el único punto que borra direcciones también la rechaza, el hook de dhclient impide que el lease de la interfaz de datos reemplace la ruta por defecto de gestión, y cada script compara la IPv4 de `ens3` al empezar y al terminar y avisa si cambió. `servicios_coubicados.sh` y `ovs_switch.sh` también la rechazan.

**Interfaz de la sesión.** A `login.sh` y `logout.sh` se les pasa la interfaz de datos: con el túnel VXLAN, `vx-sw2` en h1 y h2 y `vx-sw3` en h3 y h4 (`ens4` queda como portadora del túnel y también se rechaza); sin túnel, `ens4`. Sin argumento eligen la interfaz de túnel del host. Los comandos para montar el túnel están en el paso 11 bis.

**Si un host pierde su IP de gestión** (PuTTY da «Connection timed out» y `ip -br addr` muestra `ens3` sin IPv4), se recupera desde la consola del VNRT. En `root@h2:~#` ejecutar:

```bash
networkctl reconfigure ens3
ip -br addr show ens3
```

Si `ens3` sigue sin IPv4, ejecutar `dhclient -v ens3`. No usar `netplan apply` ni reiniciar la VM: pueden deshacer la configuración del túnel en `ens4`.

### Paso 11 bis. Túneles VXLAN host ↔ switch (provisionales hasta la opción A)

La red virtual del slice descarta las tramas a las direcciones de grupo reservadas, entre ellas las de 802.1X (01:80:c2:00:00:03). Mientras el laboratorio no habilite su reenvío (opción A), cada host llega a su switch de acceso por un túnel VXLAN sobre el enlace de datos: el host manda sus tramas por `vx-swX` y el switch las recibe por el puerto `vx-hN` de su bridge. Las tramas 802.1X viajan intactas dentro del túnel.

Los túneles se configuran a mano y **no son persistentes**: se vuelven a montar antes de cada validación. La configuración por defecto del LAB5 (netplan) los deshace cuando se reinicia `systemd-networkd` (por ejemplo, con una actualización automática nocturna) o cuando se ejecuta `networkctl reconfigure` sobre `ens4`, `ens5` o `ens6`: en los hosts, `ens4` vuelve a 10.0.0.N/24; en los switches, `ens5` y `ens6` pierden su IP y vuelven al bridge, y los puertos `vx-h*` pueden desaparecer.

| Switch | Interfaz | IP | Puerto del túnel | VNI | Puerto OpenFlow | Host | IP en `ens4` | Interfaz del host |
|---|---|---|---|---|---|---|---|---|
| sw2 | ens5 | 172.31.21.2/30 | vx-h1 | 1001 | 2 | h1 | 172.31.21.1/30 | vx-sw2 |
| sw2 | ens6 | 172.31.22.2/30 | vx-h2 | 1002 | 3 | h2 | 172.31.22.1/30 | vx-sw2 |
| sw3 | ens5 | 172.31.23.2/30 | vx-h3 | 1003 | 1 | h3 | 172.31.23.1/30 | vx-sw3 |
| sw3 | ens6 | 172.31.24.2/30 | vx-h4 | 1004 | 2 | h4 | 172.31.24.1/30 | vx-sw3 |

*Tabla 4 bis. Túneles VXLAN del slice. El uplink (`ens4`) es el puerto 1 en sw2 y el 3 en sw3; los puertos de host coinciden con los que registra M7 (`tel354:base`: hosts [2,3] y [1,2]).*

**Revisión rápida.** En cada host, `ping -c 4 -W 1 172.31.2N.2` (N = 1 a 4). Si responde, no hay nada que hacer. Si falla, se monta el túnel de ese par con los bloques siguientes, primero en el switch y luego en el host.

En `root@sw2:~#` ejecutar:

```bash
BR=$(ovs-vsctl list-br | head -1)
ovs-vsctl --if-exists del-port $BR ens5; ovs-vsctl --if-exists del-port $BR ens6
ip addr replace 172.31.21.2/30 dev ens5; ip addr replace 172.31.22.2/30 dev ens6
ovs-vsctl --may-exist add-port $BR vx-h1 -- set interface vx-h1 type=vxlan options:remote_ip=172.31.21.1 options:local_ip=172.31.21.2 options:key=1001 ofport_request=2
ovs-vsctl --may-exist add-port $BR vx-h2 -- set interface vx-h2 type=vxlan options:remote_ip=172.31.22.1 options:local_ip=172.31.22.2 options:key=1002 ofport_request=3
ovs-vsctl list-ports $BR; ovs-ofctl -O OpenFlow13 show $BR | grep -E "\((ens|vx)"
```

En `root@sw3:~#` ejecutar:

```bash
BR=$(ovs-vsctl list-br | head -1)
ovs-vsctl --if-exists del-port $BR ens5; ovs-vsctl --if-exists del-port $BR ens6
ip addr replace 172.31.23.2/30 dev ens5; ip addr replace 172.31.24.2/30 dev ens6
ovs-vsctl --may-exist add-port $BR vx-h3 -- set interface vx-h3 type=vxlan options:remote_ip=172.31.23.1 options:local_ip=172.31.23.2 options:key=1003 ofport_request=1
ovs-vsctl --may-exist add-port $BR vx-h4 -- set interface vx-h4 type=vxlan options:remote_ip=172.31.24.1 options:local_ip=172.31.24.2 options:key=1004 ofport_request=2
ovs-vsctl list-ports $BR; ovs-ofctl -O OpenFlow13 show $BR | grep -E "\((ens|vx)"
```

Resultado esperado: los puertos del bridge son `ens4` y los dos `vx-h*` (no `ens5` ni `ens6`), con los números de la Tabla 4 bis.

En `root@h1:~#` ejecutar:

```bash
ip link del vx-sw2 2>/dev/null
ip addr flush dev ens4 scope global; ip addr add 172.31.21.1/30 dev ens4
ip link add vx-sw2 type vxlan id 1001 remote 172.31.21.2 local 172.31.21.1 dev ens4 dstport 4789; ip link set vx-sw2 up
sleep 2; ping -c 4 -W 1 172.31.21.2
```

En `root@h2:~#` ejecutar:

```bash
ip link del vx-sw2 2>/dev/null
ip addr flush dev ens4 scope global; ip addr add 172.31.22.1/30 dev ens4
ip link add vx-sw2 type vxlan id 1002 remote 172.31.22.2 local 172.31.22.1 dev ens4 dstport 4789; ip link set vx-sw2 up
sleep 2; ping -c 4 -W 1 172.31.22.2
```

En `root@h3:~#` ejecutar:

```bash
ip link del vx-sw3 2>/dev/null
ip addr flush dev ens4 scope global; ip addr add 172.31.23.1/30 dev ens4
ip link add vx-sw3 type vxlan id 1003 remote 172.31.23.2 local 172.31.23.1 dev ens4 dstport 4789; ip link set vx-sw3 up
sleep 2; ping -c 4 -W 1 172.31.23.2
```

En `root@h4:~#` ejecutar:

```bash
ip link del vx-sw3 2>/dev/null
ip addr flush dev ens4 scope global; ip addr add 172.31.24.1/30 dev ens4
ip link add vx-sw3 type vxlan id 1004 remote 172.31.24.2 local 172.31.24.1 dev ens4 dstport 4789; ip link set vx-sw3 up
sleep 2; ping -c 4 -W 1 172.31.24.2
```

Resultado esperado: los cuatro ping responden. En `onos@root >`, `tel354:base` muestra hosts [2,3] en sw2 y [1,2] en sw3, con 7/7 reglas y 2/2 meters.

Si un ping falla, el diagnóstico es: en el switch, `timeout 10 tcpdump -eni ens5 -c 8 "arp or icmp"` mientras el host hace ping. Si llegan las ARP del host y el switch no responde, revisar con `ovs-vsctl list-ports` que `ens5` o `ens6` no hayan vuelto al bridge (OVS se lleva sus tramas antes que la pila IP) y que tengan su IP.

**Qué no hacer mientras haya túneles:** `netplan apply`, `networkctl reconfigure` sobre `ens4`, `ens5` o `ens6`, ni reiniciar las VMs: devuelven la configuración del LAB5 y deshacen los túneles. `ens3` no se toca en ningún caso.

**Cuando se habilite la opción A.** Ya no habrá túneles: en los switches, `ens5` y `ens6` vuelven al bridge como puertos de host (sin IP) y se borran los `vx-h*`; en los hosts, se borra `vx-swX` y `ens4` queda sin IPv4 hasta el login, que entonces se hace con `login.sh` sin argumento y entrega la IP de usuario en `ens4`. M7 detecta los puertos nuevos y les instala sus reglas 800 y meters (prueba B8).

## 6. Pruebas de M1

Ventanas recomendadas: el host a la izquierda y, a la derecha, `root@controller` con `docker logs -f --since 1m onos 2>&1 | grep -E "M1|M5"`; `root@sw2:~#` para las reglas. En `onos-cli` ejecutar una vez `log:set DEBUG pe.edu.pucp.tel354.m1`. Antes de cada prueba, `date` en el host para cruzar los tiempos con el log. Las pruebas son las de la Guía de M1 (Sección 13), con los nombres del LAB5.

| # | Host y comando | Resultado esperado |
|---|---|---|
| V1 | h1: `ping -c 2 -W 1 10.200.1.10` | Sin IP y sin respuesta |
| V2 | h1: `bash r1-identidad/host/login.sh vx-sw2` (alumno01 / Alumno2026!) y luego `curl -s http://10.200.1.10:443/` | IP 10.17.x.y/12, gateway 10.16.0.1; responde el Campus Virtual. En ONOS: EAPOL-Start, Access-Accept, AUTENTICADA, primer DHCPDISCOVER, ACTIVA |
| V3 | sw2: `ovs-ofctl -O OpenFlow13 dump-flows sw2 --no-stats \| grep -E "priority=(525\|520\|505\|200\|190\|110)"` | Las 6 reglas de la sesión; 525 y 505 con `meter:` |
| V4 | h3: login con docente01 (Docente2026!); h4: login con jp01 (Jp2026!) | 10.50.x.y y 10.34.x.y, VLAN 102 |
| V5 | h2: login con alumno02 y `nmap -sn -n 10.17.0.0/24` | `0 hosts up`; ArpProbe en el log |
| V6 | h2: `hping3 -S -a 10.17.0.77 -p 443 -c 3 10.200.1.10` y `timeout 5 arpspoof -i vx-sw2 10.16.0.1` | BindingViolation (IP) y BindingViolation (ARP) inequívoca |
| V7 | ONOS: `tel354:sesiones` y `tel354:sesion-revocar <id de h2>` | El puerto de h2 baja 5 s; EAP-Request/Identity; h2 se reautentica solo |
| V8 | sw2: `ovs-ofctl -O OpenFlow13 mod-port sw2 <puerto de h1> down; sleep 3; ovs-ofctl -O OpenFlow13 mod-port sw2 <puerto de h1> up` | Sesión de h1 cerrada con DESCONEXION |
| V8' | h1: `SIN_DHCP=1 bash r1-identidad/host/login.sh vx-sw2` | A los 60 s, `sin DHCP en 60 s` y cierre con DESCONEXION |
| V10 | h1: `bash r1-identidad/host/logout.sh vx-sw2` | Reglas retiradas y sesión CERRADA |

*Tabla 4. Pruebas de M1 en el LAB5. El número de puerto de cada host en sw2 lo muestra `ovs-ofctl -O OpenFlow13 show sw2`.*

**Capturas F11 a F20:** una por prueba, en el orden de la tabla (V1 = F11, V2 = F12, V3 = F13, V4 = F14, V5 = F15, V6 = F16, V7 = F17, V8 = F18, V8' = F19 y V10 = F20), con el host y el log lado a lado.

En `root@controller:~#` ejecutar para ver las sesiones en la base:

```bash
su postgres -c "psql -d campus -c \"SELECT s.id, u.uid_ldap, s.switch_dpid, s.puerto, host(s.ip), s.vlan_id, s.estado, s.motivo_cierre FROM sesion s JOIN usuario u ON u.id = s.usuario_id ORDER BY s.id DESC LIMIT 10\""
```

**Captura F21:** la salida de la consulta.

## 7. Pruebas de M5

Se siguen las pruebas P1 a P11 de la Guía de M5 (Sección 6), con esta correspondencia: Kali-1 → h2 (alumno02, sw2), Kali-2 → h4 (jp01, sw3), Ubuntu-1 → h1 (alumno01) y Ubuntu-2 → h3 (docente01). Con una sola instancia, todos los comandos se ejecutan en `onos@root >`, y la P7 se descarta en la misma instancia en lugar de otra.

Antes, en `root@controller:~/tel354-campus-sdn#` ejecutar para que N2 venza en dos minutos:

```bash
sed 's/"n2Seg": 3600/"n2Seg": 120/' r3-ataques-encubiertos/config/r3-netcfg.json > /tmp/r3-prueba.json
curl -s -u onos:rocks -X POST -H "Content-Type: application/json" http://127.0.0.1:8181/onos/v1/network/configuration -d @/tmp/r3-prueba.json
```

| # | Comando (con el id de la sesión de `tel354:sesiones`) | Dónde mirar |
|---|---|---|
| P1 | `tel354:r3-simular <h2> P --destino 10.200.1.30:tcp:21,22,23,25,80,110,135,139,443,445` | sw2: regla 300 hacia 10.200.1.30 (900 s); `tel354:reserva` = 1 |
| P2 | `tel354:r3-simular <h2> N --destino 10.200.1.30:tcp:5432` | sw2: 302, 301 ×2 y 300 con `meter:`; `dump-meters sw2`; en h2, el DNS responde y el Campus Virtual no |
| P3 | `tel354:r3-simular-contador <incidente P2>` | `renovado por 120 s` |
| P4 | h2: `logout.sh vx-sw2` y `login.sh vx-sw2` | Las 4 reglas con la IP nueva |
| P5 | Esperar 120 s | `venció sin renovación`; reserva en 0 |
| P6 | controller: `curl -s -u onos:rocks -X POST -H "Content-Type: application/json" http://127.0.0.1:8181/onos/campus/v1/incidentes/<P2>/confirmar -d '{"minutos": 10, "motivo": "validación P6"}'` | h2 pasa a 10.113.x.y (VLAN 999); solo DNS |
| P7 | `tel354:r3-simular <h4> D --destino 10.200.0.5 --destino 10.200.0.13 … (8 destinos)` y luego `tel354:incidente-descartar <P7> --motivo "validación"` | sw3: N2 y luego sin reglas |
| P8 | Cargar `/tmp/r3-prueba.json` con `"reservaPorSwitch": 2` y `tel354:r3-simular <h1> D …` | `reserva insuficiente`, incidente en N0 con `reserva_llena` |
| P9, P10 | `tel354:r3-simular <h4> SUPLANTACION --destino 10.34.0.77` y luego `… --destino 10.32.0.1 --inequivoca` | N2 + PRIORITARIA; después, confirmación automática y h4 en 10.114.x.y |
| P11 | `tel354:r3-modo OBSERVACION`, `tel354:r3-simular <h3> P --destino 10.200.3.12:tcp:22,80,443,1812,5432,636,8000,8181,3306,25`, `tel354:r3-modo ACTIVO` | Incidente en N0 con nivel calculado N1; sin reglas en sw3 |

*Tabla 5. Pruebas de M5 en el LAB5 (comandos completos en la Guía de M5).*

**Sintaxis en la consola de ONOS (Karaf 4.2):** `tel354:r3-simular` quiere las opciones **antes** de los argumentos y el destino entre comillas:

```
tel354:r3-simular --destino "10.200.1.30:tcp:21,22,23,80,443" <sesion> P
tel354:r3-simular --destino "10.32.0.1" --inequivoca <sesion> SUPLANTACION
```

**Capturas F22 a F29:** P1 = F22, P2 = F23, P3 y P5 = F24, P6 = F25, P7 = F26, P8 = F27, P9 y P10 = F28, y P11 = F29. En cada una, lo indicado en la columna «Dónde mirar».

### Paso 12. Cadena completa con tráfico real (Scapy)

Esta prueba necesita M2, M3 y M4a, que los Pasos 3 y 5 no instalan. Se instalan después de P1 a P11, porque el script vuelve a cargar `r3-netcfg.json` y deshace el cambio de N2 a 120 s. En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
TEL354_ENV=una_instancia.env bash r3-ataques-encubiertos/instalar_r3.sh m2 m3 m4a
```

**Captura F30:** `tel354.m2`, `tel354.m3` y `tel354.m4a` instaladas y activas.

Las pruebas P1 a P11 inyectan la sospecha directamente en M5. Para validar la cadena entera (M2 deniega y publica → M4a cuenta en ventanas → M5 contiene) se genera tráfico real desde un Kali con `r3-ataques-encubiertos/pruebas/ataque.py`, que manda los paquetes a ritmo fijo y deja un CSV con la hora de cada envío. Scapy ya quedó instalado en h2 y h4 en el Paso 11.

En `root@h2:~#` (alumno02, con la sesión ACTIVA) ejecutar:

```bash
python3 r3-ataques-encubiertos/pruebas/ataque.py puertos --destino 10.200.1.30 --puertos 20-35 --tasa 2
python3 r3-ataques-encubiertos/pruebas/ataque.py red --destinos 10.200.1.31-45 --puerto 22 --tasa 2
python3 r3-ataques-encubiertos/pruebas/ataque.py ip-falsa --destino 10.200.1.10 --ip-falsa 10.17.0.77
```

| Escenario | Umbral que dispara | Resultado esperado en ONOS |
|---|---|---|
| `puertos` (SYN a 16 puertos de un destino) | P1 = 10 en W1 = 10 s | THREAT_SUSPECTED (ESCANEO_PUERTOS); M5 instala la contención en sw2 |
| `red` (SYN a 15 destinos) | D1 = 8 en W1 = 10 s | THREAT_SUSPECTED (ESCANEO_RED) |
| `ip-falsa` (SYN con IP de origen ajena) | BindingViolation, sin umbral | SUPLANTACION inmediata |

**Capturas F31 a F33:** un escenario por captura, con h2 y el log lado a lado. **F34:** `tel354:incidentes`. Se envían también los tres CSV que deja el script.

*Tabla 5 bis. Pruebas de M5 con tráfico real. El CSV de cada corrida se cruza con `docker logs onos` para medir el tiempo de detección (hora del THREAT_SUSPECTED menos la del envío que cruzó el umbral).*

El punto ciego es medible y conviene reportarlo: un escaneo más lento que ambos umbrales ($r < P_2/W_2 = 30/600 = 0{,}05$ puertos/s) no se detecta; a ese ritmo, 1000 puertos toman unas 5,6 h. Fuerza bruta y petición maliciosa necesitan sesiones TCP/HTTP reales (hydra, curl): quedan para cuando se integre el IDS.

Para terminar las sanciones de P6 y P10 antes de tiempo, en `root@controller:~#` ejecutar y repetir el login del host:

```bash
su postgres -c "psql -d campus -c \"UPDATE cuarentena SET inicio = now() - interval '2 min', fin = now() - interval '1 min' WHERE fin > now() AND incidente_id IN (SELECT id FROM incidente WHERE fuente = 'SIM')\""
```

### Paso 12 bis. Pruebas locales antes del slice

Dos bancos de prueba corren en controller (o en cualquier equipo con Java y bash) sin tocar la red. En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
bash r1-identidad/host/probar_host.sh
bash comun/onos-app-base/banco/banco.sh
```

Resultado esperado: `Resultado: todas las verificaciones pasan` en ambos.

- `probar_host.sh` ejecuta `login.sh`, `logout.sh`, `wpa_accion.sh` y el hook de dhclient con `ip`, `wpa_supplicant`, `wpa_cli` y `dhclient` simulados, en cuatro tipos de equipo (VM con túnel, sin túnel, con dos túneles y host de Mininet). Comprueba en 27 casos que `ens3` conserva su IP y su ruta por defecto, y que ningún comando la toca. Con los scripts de la v3 da 20 fallas: reproduce el problema de h2.
- `banco.sh` ejecuta la app `tel354.base` real contra un ONOS y unos switches simulados (reglas y meters asíncronos, rechazo de una regla cuyo meter aún no existe, reinstalación desde el almacén) y recorre 15 escenarios con 44 verificaciones: los de B2 a B11 y casos que no se pueden provocar a voluntad en el slice (reinicio de la app sin enlaces descubiertos, pérdida de un enlace, meter borrado por fuera, pabellón apagado al arrancar).

**Captura F34 bis:** la última línea de los dos bancos.

## 7 bis. Pruebas de tel354.base (M7 + M8)

La app instala el régimen base al detectar cada switch: Tabla 0 (900, 800 con meter `ctl_P`, 600, 10) con el appId `tel354.m7`, y Tabla 2 fija y core con `tel354.m8`. La Tabla 1 es de M2: hasta la versión 1.7 la cargaba el puente `base_auto.py --solo-tabla1` (paso 15); desde la 1.8 la instala la app M2 (sección 7 quater). Antes de empezar, cerrar sesión en los cuatro hosts (`logout.sh vx-sw2` en h1 y h2, `logout.sh vx-sw3` en h3 y h4).

| Prueba | Qué verifica | Paso | Captura |
|---|---|---|---|
| B0 | El conjunto de reglas, sin ONOS | 14 | Salida del instalador |
| B1 | La app compila contra ONOS 2.7 real y se activa | 14 | F35 |
| B2 | Régimen del slice al migrar desde `base_auto.py` | 14 | F35, F36 |
| B3 | appId y cookie por módulo; regresión de M1 | 15 | F37, F38 |
| B4 | Idempotencia: reinicio del bundle con la app activa | 16 | F39 |
| B5 | Pabellón nuevo: una fila y el switch se conecta | 17 | F40 |
| B6 | La base de datos manda: reasignar un pabellón | 18 | F41 |
| B7 | Switch que no está en `switch_acceso` | 19 | F42 |
| B8 | Puerto de host nuevo y puerto retirado | 20 | F43 |
| B9 | Reinicio del switch: pierde reglas y meters | 21 | F44 |
| B10 | Caída de PostgreSQL | 22 | F45 |
| B11 | Retiro en orden por la dependencia de M1 | 23 | F46 |
| B12 | Convivencia con M5 y M3 | 24 | F47 |

*Tabla 5 ter. Plan de pruebas de M7 y M8. Los criterios de aceptación están en la pestaña «Pruebas de M7 y M8» del documento de validación.*

Los hosts necesitan los scripts nuevos de `r1-identidad/host/` (protegen la interfaz de gestión). En `root@controller:~/tel354-campus-sdn#` ejecutar (contraseña `ubuntu`):

```bash
for ip in 192.168.0.21 192.168.0.22 192.168.0.23 192.168.0.24; do
  scp -rq r1-identidad/host ubuntu@$ip:/tmp/
  ssh -t ubuntu@$ip "sudo cp /tmp/host/* /root/tel354-campus-sdn/r1-identidad/host/"
done
```

### Paso 13. Rol onos_m7 en PostgreSQL

Se agrega el rol sin rehacer la base (02_roles.sql es idempotente).

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
su postgres -c "psql -q -d campus" < comun/db/02_roles.sql
PGV=$(ls /etc/postgresql | sort -n | tail -1); HBA=/etc/postgresql/$PGV/main/pg_hba.conf
grep -q onos_m7 $HBA || sed -i '/onos_m6/{p;s/onos_m6/onos_m7/}' $HBA
systemctl reload postgresql
PGPASSWORD=m7-demo psql "host=10.200.3.12 dbname=campus user=onos_m7 sslmode=require" -c "SELECT * FROM switch_acceso"
```

Resultado esperado: las dos filas (`of:0000000000000001` → 1, `of:0000000000000002` → 2).

### Paso 14. Migración: de base_auto.py a la app

Se retiran las apps que dependen del régimen base, se instala `tel354.base` (con `--migrar`, que borra lo que dejó `base_auto.py`), se carga el puente de la Tabla 1 y se reinstalan las apps en orden. M1 se recompila porque ahora declara `tel354.base` en `onos.app.requires`: ONOS no lo instala sin ella.

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
export TEL354_ENV=una_instancia.env
bash r3-ataques-encubiertos/onos-app-m5/instalar_m5.sh --desinstalar
curl -s -u onos:rocks -X DELETE http://127.0.0.1:8181/onos/v1/applications/tel354.m3
bash r1-identidad/onos-app-m1/instalar_m1.sh --desinstalar
bash comun/onos-app-base/instalar_base.sh --migrar 2>&1 | tee /root/mvn.log
```

Resultado esperado: `Resultado: todas las verificaciones pasan` (prueba del conjunto de reglas sin ONOS) y `Estado: ACTIVE | versión 2.0.0`.

En `onos@root >` (ventana D) ejecutar:

```
tel354:base
```

Resultado esperado:

| switch | rol | uplink | hosts | reglas | meters |
|---|---|---|---|---|---|
| of:0000000000000001 | acceso 1 | 1 | [2,3] | 7/7 | 2/2 |
| of:0000000000000002 | acceso 2 | 3 | [1,2] | 7/7 | 2/2 |
| of:00000000000000c1 | core | - | [] | 20/20 | - |

**Captura F35:** `tel354:base`.

En `root@controller:~/tel354-campus-sdn#` (ventana B) ejecutar:

```bash
docker logs onos 2>&1 | grep -E "M7|M8" | tail -20
```

Resultado esperado: una línea `régimen base completo` por switch, con los dos tiempos (Flow-Mods confirmados y reglas ADDED).

**Captura F36:** el log.

### Paso 15. Reglas instaladas y puente de la Tabla 1

En `onos@root >` ejecutar:

```
tel354:base -r
```

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
python3 r2-autorizacion/proactivo/base_auto.py --solo-tabla1
bash r1-identidad/onos-app-m1/instalar_m1.sh
bash r3-ataques-encubiertos/instalar_r3.sh m3
bash r3-ataques-encubiertos/onos-app-m5/instalar_m5.sh
```

Resultado esperado: `Tabla 1 con 21 reglas` en cada switch de acceso (7 + 21 = 28, igual que antes) y las cuatro apps ACTIVE. Luego, en h1, repetir V2 y V3 (login, DHCP y reglas de sesión).

En `root@sw2:~#` ejecutar:

```bash
ovs-ofctl -O OpenFlow13 dump-flows $(ovs-vsctl list-br | head -1) | grep -E "priority=(900|800|600|525|505)"
```

Resultado esperado: la 800 de cada puerto de host con `meter:N`, y las 525 y 505 de la sesión de h1 con el mismo `meter:N` que la 800 de su puerto (M1 lo toma de ahí).

**Captura F37:** `tel354:base -r` y la salida del puente. **F38:** el `dump-flows` con la sesión de h1 activa.

### Paso 16. Idempotencia: reinicio de la app sin desactivarla

Con tráfico de h1 hacia el Campus Virtual, se reinicia el bundle (la app sigue ACTIVE, así que no retira nada). El tráfico es TCP 443, permitido al rol: un ping no sirve, porque el ICMP no está en la matriz de permisos y la regla 55 lo envía al controlador, donde nadie lo responde hasta que exista M2.

En `root@h1:~/tel354-campus-sdn#` ejecutar (dura unos 30 s):

```bash
for i in $(seq 60); do curl -s -m 1 -o /dev/null -w "%{http_code} " http://10.200.1.10:443/; sleep 0.5; done; echo
```

Mientras corre, en `onos@root >` ejecutar (el id es el de `tel354-base`, no el de `_onos-gui2-base-jar`):

```
bundle:list | grep -i base
bundle:restart <id de tel354-base>
tel354:base
```

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
docker logs onos 2>&1 | grep -E "M7|M8" | tail -8
```

Resultado esperado: `M7+M8 detenidos (instancia apagada)`, `M7+M8 iniciados` y `régimen base completo ... Flow-Mods confirmados a los - ms` (ningún Flow-Mod: no aparece ninguna línea `+N -M reglas`). Las 60 respuestas son `200` y los meters siguen siendo los mismos.

**Captura F39:** el log, `tel354:base` y la salida del `curl`.

### Paso 17. Pabellón nuevo: una fila y el switch se conecta

Se simula un pabellón nuevo con sw3: se le da un datapath-id que ONOS nunca vio (`0000000000000003`) y se agrega su fila en `switch_acceso`. Es el caso real: ni el perfil ni ONOS cambian. (Desconectar y reconectar el mismo switch no sirve: ONOS conserva sus reglas y meters, porque `purgeOnDisconnection` es `false`, y los reinstala él mismo.)

En `root@controller:~#` ejecutar:

```bash
su postgres -c "psql -d campus -c \"INSERT INTO pabellon VALUES (3, 'Pabellón 3 (prueba)', 103) ON CONFLICT DO NOTHING; INSERT INTO switch_acceso VALUES ('of:0000000000000003', 3, '172.16.0.99') ON CONFLICT DO NOTHING\""
```

En `root@sw3:~#` ejecutar:

```bash
BR=$(ovs-vsctl list-br | head -1); CTL=$(ovs-vsctl get-controller $BR | xargs); echo "$BR -> $CTL"
ovs-vsctl del-controller $BR
ovs-vsctl set bridge $BR other-config:datapath-id=0000000000000003
ovs-ofctl -O OpenFlow13 del-flows $BR; ovs-ofctl -O OpenFlow13 del-meters $BR
ovs-vsctl set-controller $BR $CTL
```

A los 15 s, en `onos@root >` ejecutar:

```
tel354:base
```

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
docker logs onos 2>&1 | grep -E "M7|M8" | tail -8
```

Resultado esperado: `switch_acceso con 3 switches de acceso` (se relee al aparecer el switch), `M7: of:0000000000000003 (acceso 3): +2 -0 reglas [sin enlace con el core]`, luego las 5 restantes, `M8: of:00000000000000c1 (core): +9 -0 reglas` (pabellón 3) y `régimen base completo` con sus dos tiempos. En `tel354:base`, el switch nuevo con 7/7 y 2/2, y el core con 29/29 ($9 \cdot 3 + 2$). El tiempo se mide desde la línea `Device of:0000000000000003 connected` de ONOS (`docker logs onos 2>&1 | grep -i 00:00:00:00:00:00:00:03`), que es también desde donde cuenta la app: la lectura de `switch_acceso` que aparece antes puede venir de la revisión periódica, cuando la fila se inserta antes de reconectar el switch. Una corrida basta para validar; para un rango de tiempos, repetir con datapath-id nuevos (05, 06…), porque reconectar el mismo switch no lo hace nuevo para ONOS. La Tabla 1 del switch nuevo no se carga: el puente REST depende del perfil, y eso es justamente lo que debe asumir M2.

Para devolver sw3 a su identidad, en `root@sw3:~#` ejecutar:

```bash
ovs-vsctl del-controller $BR
ovs-vsctl set bridge $BR other-config:datapath-id=0000000000000002
ovs-ofctl -O OpenFlow13 del-flows $BR; ovs-ofctl -O OpenFlow13 del-meters $BR
ovs-vsctl set-controller $BR $CTL
```

En `onos@root >` ejecutar `device-remove of:0000000000000003` y, en `root@controller:~#`:

```bash
su postgres -c "psql -d campus -c \"DELETE FROM switch_acceso WHERE dpid = 'of:0000000000000003'\""
```

ONOS reinstala por su cuenta las reglas (≤ 5 s) y los meters (≤ 10 s) de `of:0000000000000002`, porque siguen en su almacén. Esa reinstalación no la hace la app: en el log no aparece ninguna línea `+N -M` de M7 para ese switch.

**Captura F40:** `tel354:base` con el switch nuevo, el log de una corrida y la tabla con los tres tiempos.

### Paso 18. La base de datos manda: reasignar un pabellón

Se cambia el pabellón de sw3 en `switch_acceso` (de 2 a 3), sin tocar el perfil ni ONOS.

En `root@controller:~#` ejecutar:

```bash
su postgres -c "psql -d campus -c \"INSERT INTO pabellon VALUES (3, 'Pabellón 3 (prueba)', 103) ON CONFLICT DO NOTHING; UPDATE switch_acceso SET pabellon_id = 3 WHERE dpid = 'of:0000000000000002'\""
sleep 35; docker logs onos 2>&1 | grep -E "M7|M8" | tail -4
su postgres -c "psql -d campus -c \"UPDATE switch_acceso SET pabellon_id = 2 WHERE dpid = 'of:0000000000000002'\""
```

Resultado esperado: en menos de 30 s, `switch_acceso con 2 switches de acceso: {…01=1, …02=3}` y `M8: of:00000000000000c1 (core): +9 -9 reglas` (VLAN 102 → 103). Las reglas de sw3 no cambian: no dependen del número de pabellón. Al revertir, vuelve a +9 -9.

**Captura F41:** el log.

### Paso 19. Switch que no está en switch_acceso (B7)

sw3 se conecta con un datapath-id sin fila en la base (`0000000000000004`). Después de cada paso de esta sección, el estado se comprueba con `tel354:base` en `onos@root >` y con el log (`docker logs onos 2>&1 | grep -E "M7|M8" | tail -8`) en la ventana B.

En `root@sw3:~#` ejecutar:

```bash
BR=$(ovs-vsctl list-br | head -1); CTL=$(ovs-vsctl get-controller $BR | xargs); echo "$BR -> $CTL"
ovs-vsctl del-controller $BR
ovs-vsctl set bridge $BR other-config:datapath-id=0000000000000004
ovs-ofctl -O OpenFlow13 del-flows $BR; ovs-ofctl -O OpenFlow13 del-meters $BR
ovs-vsctl set-controller $BR $CTL
```

Resultado esperado, a los 15 s: en `tel354:base`, `of:0000000000000004` con rol `sin rol`, reglas 0/0 y la nota `no está en switch_acceso`. El core sigue en 20/20.

Para devolver sw3 a su identidad, en `root@sw3:~#` ejecutar:

```bash
ovs-vsctl del-controller $BR
ovs-vsctl set bridge $BR other-config:datapath-id=0000000000000002
ovs-ofctl -O OpenFlow13 del-flows $BR; ovs-ofctl -O OpenFlow13 del-meters $BR
ovs-vsctl set-controller $BR $CTL
```

En `onos@root >` ejecutar:

```
device-remove of:0000000000000004
```

**Captura F42:** `tel354:base` con el switch `sin rol`.

### Paso 20. Puerto de host nuevo y puerto retirado (B8)

En `root@sw2:~#` ejecutar:

```bash
BR=$(ovs-vsctl list-br | head -1)
ovs-vsctl add-port $BR prueba0 -- set interface prueba0 type=internal
```

Resultado esperado, a los 5 s: sw2 con un puerto más en `hosts`, 8/8 reglas y 3/3 meters; en el log, `+1 -0 reglas`.

En `root@sw2:~#` ejecutar:

```bash
ovs-vsctl del-port $BR prueba0
```

Resultado esperado: sw2 vuelve a 7/7 y 2/2; en el log, `meter N sin puerto de host: se retira` y `+0 -1 reglas`.

**Captura F43:** `tel354:base` con 8/8 y luego con 7/7, y el log.

### Paso 21. Reinicio del switch (B9)

sw2 pierde sus reglas y meters, pero conserva su datapath-id. Con h1 con sesión abierta.

En `root@sw2:~#` ejecutar:

```bash
BR=$(ovs-vsctl list-br | head -1); CTL=$(ovs-vsctl get-controller $BR | xargs)
ovs-vsctl del-controller $BR
ovs-ofctl -O OpenFlow13 del-flows $BR; ovs-ofctl -O OpenFlow13 del-meters $BR
ovs-vsctl set-controller $BR $CTL
sleep 30
ovs-ofctl -O OpenFlow13 dump-flows $BR | grep -E "priority=(900|800|600|525|505)"
ovs-ofctl -O OpenFlow13 dump-meters $BR | grep -c "meter="
```

Resultado esperado: la 900, las dos 800 con `meter:N` y la 600 de vuelta, y los dos meters `ctl_P` (más los dos del puente de la Tabla 1). En el log no hay líneas `+N -M` para `of:0000000000000001`: reinstala ONOS, no la app. Se anota si las 525 y 505 de h1 también vuelven. Es la prueba de resultado menos seguro: las 800 dependen de que ONOS reinstale primero sus meters; si alguna no vuelve en 30 s, es un hallazgo.

**Captura F44:** el `dump-flows`, el conteo de meters y el log.

### Paso 22. Caída de PostgreSQL (B10)

Sin logins durante la prueba: RADIUS y Kea también usan la base.

En `root@controller:~#` ejecutar:

```bash
systemctl stop postgresql
sleep 40; docker logs onos 2>&1 | grep -E "M7|M8" | tail -3
systemctl start postgresql
sleep 35; docker logs onos 2>&1 | grep -E "M7|M8" | tail -2
```

Resultado esperado: una sola línea `no se pudo leer switch_acceso (…); se mantiene lo último conocido`, sin líneas `+N -M`. Al volver la base, `switch_acceso con 2 switches de acceso`. `tel354:base` no cambia en ningún momento.

**Captura F45:** la salida de los dos `docker logs`.

### Paso 23. Retiro en orden (B11)

Retira M1 y M5 y los vuelve a activar. Los hosts sin sesión.

En `onos@root >` ejecutar:

```
app deactivate tel354.base
apps -s | grep tel354
```

En `root@controller:~#` ejecutar:

```bash
for a in tel354.m7 tel354.m8; do curl -s -u onos:rocks http://127.0.0.1:8181/onos/v1/flows/application/$a | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["flows"]))'; done
curl -s -u onos:rocks http://127.0.0.1:8181/onos/v1/meters | python3 -c 'import json,sys; print(sum(m.get("appId") == "tel354.m7" for m in json.load(sys.stdin)["meters"]))'
docker logs onos 2>&1 | grep detenido | tail -3
```

Resultado esperado: solo `tel354.api` y `tel354.m3` siguen activas (con `*`); los tres conteos dan 0; en el log, `M5 detenido`, `M1 detenido` y `M7+M8 detenidos (app desactivada)`, en ese orden.

En `onos@root >` ejecutar:

```
app activate tel354.m1
app activate tel354.m5
tel354:base
```

Resultado esperado: activar M1 activa también `tel354.base`, y el régimen vuelve a 7/7, 7/7 y 20/20.

**Captura F46:** `apps -s`, los tres conteos y el log.

### Paso 24. Convivencia con M5 y M3 (B12)

Se repiten la contención N2 (P1 de la sección 7, con dos destinos críticos) y las pruebas de contadores de M3: C2 (con tráfico, la contención se renueva) y C3 (sin tráfico, vence). El ping va **antes** de la contención: con `n2Seg` en 30, sin tráfico la contención vence a los 30 s y no da tiempo de mirar el switch. Se usa un usuario sin incidentes en las últimas 24 h (en `tel354:incidentes`): uno nuevo dentro de ese plazo cuenta como reincidencia y sube un nivel.

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
sed 's/"n2Seg": 3600/"n2Seg": 30/' r3-ataques-encubiertos/config/r3-netcfg.json > /tmp/r3-prueba.json
curl -s -u onos:rocks -X POST -H "Content-Type: application/json" http://127.0.0.1:8181/onos/v1/network/configuration -d @/tmp/r3-prueba.json
docker logs -f --since 5s onos 2>&1 | grep -E "M3|M5|M7"
```

En `root@h1:~/tel354-campus-sdn#` ejecutar y dejar corriendo (la regla 301 de N2 descarta lo que va a 10.0.0.0/8, y M3 cuenta esos paquetes):

```bash
ping -i 0.5 10.200.1.10
```

En `onos@root >` ejecutar, con el id de la sesión de h1:

```
tel354:sesiones
tel354:r3-simular --destino 10.200.0.14 --destino 10.200.3.12 <id> D
tel354:incidentes
```

Con el ping corriendo, en `root@sw2:~#` ejecutar:

```bash
BR=$(ovs-vsctl list-br | head -1)
ovs-ofctl -O OpenFlow13 dump-flows $BR | grep -E "priority=(800|300|301|302)" | sed -E 's/duration=[^,]*, //; s/n_bytes=[^,]*, //'
ovs-ofctl -O OpenFlow13 dump-meters $BR | grep -c "meter="
```

Cortar el ping con Ctrl+C, esperar 40 s y, en `onos@root >`, ejecutar `tel354:base`. Al terminar, en `root@controller:~/tel354-campus-sdn#` volver a la configuración normal:

```bash
curl -s -u onos:rocks -X POST -H "Content-Type: application/json" http://127.0.0.1:8181/onos/v1/network/configuration -d @r3-ataques-encubiertos/config/r3-netcfg.json
```

Resultado esperado: un incidente nuevo en N2; las reglas 300 a 302 de M5 junto a las 800 de M7; la 300 usa el meter `contencion`, distinto de los `ctl_P` (un meter más en el conteo). Con ping, `CounterDelta` cada 5 s y `renovado por 30 s`; sin tráfico, `venció sin renovación` entre 30 y 35 s. En el log no aparece ninguna línea de M7 sobre sw2 y `tel354:base` sigue en 7/7 y 2/2.

**Captura F47:** el `dump-flows`, `tel354:incidentes` y `tel354:base`. **F47 bis:** el log de renovaciones y vencimiento.

## 7 ter. Resultados de la validación en el slice (12/11/2026)

| Prueba | Resultado | Dato clave |
|---|---|---|
| B0 | Pasa | Conjunto de reglas y banco de la app (44 verificaciones) en verde en controller |
| B1 | Pasa | Las cinco apps ACTIVE 2.0.0 (tras corregir `MeterState.REMOVED`, que no existe en ONOS 2.7.0) |
| B2 | Pasa | 7 + 7 + 20 reglas, 2 + 2 meters; las 800 salen después de sus meters, sin rechazos |
| B3 | Pasa | Login sin interfaz: `ens3` intacta; 525 y 505 con el meter de la 800; Campus Virtual `200` |
| B4 | Pasa | 0 Flow-Mods al reiniciar la app; 60/60 respuestas `200` durante el reinicio |
| B5 | Pasa | Una fila en `switch_acceso`; core de 20 a 29 reglas; t_conv = 7,2 s desde `Device connected` (cota 9,3 s) |
| B6 | Pasa | `+9 -9` en el core al reasignar y al revertir, en la revisión siguiente (≤ 30 s) |
| B9 | Pasa | ONOS reinstaló reglas y meters del switch, incluidas las 800 y la sesión de M1; la app no envió nada |
| B12 | Pasa | Meter de M5 intacto; renovación cada 5 s; vencimiento 34,1 s después de la última renovación |
| B7, B8, B10, B11 | Cubiertas por el banco local | No se repitieron en el slice |

*Tabla 5 quater. Resultados de M7 y M8 en el slice. Detalle y capturas en el documento de validación, pestaña «Pruebas de M7 y M8».*

Pendientes menores, sin efecto en el funcionamiento: la app registra dos veces su configuración al arrancar; tras reconectarse un switch, el tiempo «ADDED» del log no mide una convergencia (ONOS aún marca las reglas como ADDED); y M5 espera 2 s la confirmación de su meter, que ONOS da cada 10 s, por lo que siempre avisa.

## 7 quater. Pruebas de M2 y M6

M2 (`tel354.m2`, `r2-autorizacion/onos-app-m2`) instala la Tabla 1 en cada switch de acceso al detectarlo (el que tiene la regla 600 de M7), la recalcula con `policy_changed` y atiende el slow-path de la regla 55: DROP 150 de 8 s y `AuthorizationDenied`. Reemplaza al puente del paso 15. M6 (`r3-ataques-encubiertos/m6-auditoria`) es, por ahora, un servicio Python en controller, fuera de ONOS: lee el log del contenedor `onos` y la REST de M5, y escribe en `auth_log` con el rol `onos_m6` (solo INSERT). Antes de empezar, comprobar los túneles (revisión rápida del paso 11 bis) y cerrar sesión en los cuatro hosts.

| Prueba | Qué verifica | Paso | Captura |
|---|---|---|---|
| M2-1, M2-2 | Núcleo sin ONOS, compilación, activación y Tabla 1 al detectar cada switch | 25 | F48, F49 |
| M2-3, M2-4 | R2 sin cambios y denegación con DROP 150 | 26 | F50 |
| M2-5 | `policy_changed` sin correr scripts | 27 | F51 |
| M2-7, M2-8 | Cierre de sesión e idempotencia al reiniciar | 28 | F52 |
| M6-1 a M6-3 | Disparador, instalación y eventos en `auth_log` con sus referencias | 29 | F53, F54 |
| M6-7 | Reinicio de M6 sin filas repetidas ni perdidas | 30 | F55 |

*Tabla 5 quinquies. Pruebas de M2 y M6 en el slice.*

### Paso 25. M2 en lugar del puente de la Tabla 1

`--migrar` retira el puente (`base_auto.py --solo-tabla1 --borrar`): sus reglas y las de M2 no pueden convivir, porque OVS trata como la misma regla dos con igual tabla, prioridad y coincidencia.

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
export TEL354_ENV=una_instancia.env
bash r2-autorizacion/onos-app-m2/instalar_m2.sh --migrar 2>&1 | tee /root/mvn.log
```

Resultado esperado: `Todo OK` (núcleo sin ONOS), `instalada y activa` y `Estado: ACTIVE | versión 2.0.0`.

En `onos@root >` ejecutar:

```
tel354:m2
tel354:base
```

Resultado esperado: `Matriz: 13 permisos por rol` y `LISTEN policy_changed: conectado`; cada switch de acceso con su uplink, `21/21` en la Tabla 1 y nada pendiente. `tel354:base` sigue en 7/7, 7/7 y 20/20.

**Captura F48:** el final de la instalación, `tel354:m2` y `tel354:base`.

En `root@sw2:~#` ejecutar:

```bash
BR=$(ovs-vsctl list-br | head -1)
ovs-ofctl -O OpenFlow13 dump-flows $BR table=1 | sed -E 's/duration=[^,]*, //; s/n_bytes=[^,]*, //' | sort -t, -k3
ovs-ofctl -O OpenFlow13 dump-flows $BR table=1 | wc -l
```

Resultado esperado: 21 reglas (13 de prioridad 100, y 60, 58 ×2, 57, 55 ×2, 50 y 0); las dos 55 con `meter:N` (captura) y la 57 con otro meter (cuarentena). En el log (`docker logs onos 2>&1 | grep "M2:" | tail`), `Tabla 1 completa: 21 reglas (13 permisos y 8 comunes)` por switch. Si se ve `salida 50 en espera de las reglas 55 y 57`, es la falla cerrada: la 50 se agrega en la pasada siguiente, cuando las 55 y la 57 ya están en el switch.

**Captura F49:** el `dump-flows` de la Tabla 1.

### Paso 26. R2 sin cambios y denegación (M2-3, M2-4)

En h1 ejecutar `bash r1-identidad/host/login.sh vx-sw2` (alumno01 / Alumno2026!) y luego:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://10.200.1.10:443/
curl -m 3 http://10.200.1.30:5432/; echo "salida $?"
```

En `root@sw2:~#`, dentro de los 8 s siguientes, ejecutar:

```bash
ovs-ofctl -O OpenFlow13 dump-flows $BR table=1 | grep priority=150
```

Resultado esperado: el Campus Virtual responde `200`; la BD académica no responde (el alumno no tiene permiso); una regla 150 con `hard_timeout=8` para la cuádrupla `10.17.x.y → 10.200.1.30 tcp 5432`, y en el log `M2: AuthorizationDenied sesion N [alumno01 ALUMNO] -> 10.200.1.30 proto 6 puerto 5432`. Repetir el `curl` antes de 8 s no produce otra línea.

**Captura F50:** h1, el `dump-flows` y la línea del log.

### Paso 27. policy_changed (M2-5)

Se da al rol Alumno acceso a la BD académica y luego se retira, sin correr scripts. Por la herencia de roles, el permiso alcanza también a JP: son dos reglas 100 nuevas por switch.

En `root@controller:~#` ejecutar:

```bash
su postgres -c "psql -d campus -c 'INSERT INTO permiso_rol VALUES (1, 8)'"
sleep 3; docker logs onos 2>&1 | grep "M2:" | tail -4
su postgres -c "psql -d campus -c 'DELETE FROM permiso_rol WHERE rol_id = 1 AND recurso_id = 8'"
sleep 3; docker logs onos 2>&1 | grep "M2:" | tail -4
```

Resultado esperado: tras el INSERT, `M2: policy_changed (1 avisos, tablas [permiso_rol])`, `matriz actualizada: 15 permisos` y `Tabla 1: +2 -0` en cada switch de acceso; tras el DELETE, `13 permisos` y `+0 -2`. Entre ambos, el `curl` del paso 26 a la 5432 ya no genera `AuthorizationDenied` (el tráfico pasa por la regla 100).

**Captura F51:** las dos salidas del log.

### Paso 28. Cierre de sesión e idempotencia (M2-7, M2-8)

En h1 repetir el `curl` a la 5432 y, antes de 8 s, `bash r1-identidad/host/logout.sh vx-sw2`. Luego, en `onos@root >` ejecutar:

```
bundle:restart pe.edu.pucp.tel354.tel354-m2
```

En `root@controller:~#` ejecutar:

```bash
sleep 10; docker logs onos 2>&1 | grep "M2:" | tail -8
```

Resultado esperado: `M2: sesión N cerrada: 1 reglas de M2 retiradas` al cerrar la sesión; tras el reinicio, `Tabla 1 completa` por switch sin ninguna línea `Tabla 1: +… -…` ni meters nuevos (reutiliza reglas y meters).

**Captura F52:** el log.

### Paso 29. M6: disparador, instalación y eventos (M6-1 a M6-3)

`06_m6.sql` crea el disparador que completa `usuario_id` y `dispositivo_id` dentro de la base, de modo que `onos_m6` conserva solo INSERT.

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
su postgres -c "psql -q -d campus" < comun/db/06_m6.sql
su postgres -c "psql -d campus -Atc \"SELECT has_table_privilege('onos_m6','auth_log','INSERT'), has_table_privilege('onos_m6','sesion','SELECT')\""
export TEL354_ENV=una_instancia.env
bash r3-ataques-encubiertos/m6-auditoria/instalar_m6.sh
journalctl -u m6-auditoria -n 5 --no-pager
```

Resultado esperado: `t|f` (INSERT sí, SELECT no); `14 pruebas OK` (`Ran 14 tests … OK`), `creada /etc/tel354/m6.env para el perfil una_instancia.env`, `conexión OK` y el servicio `active (running)`; en el journal, `leyendo el log de onos`.

**Captura F53:** la salida del instalador y del journal.

En h1, login (alumno01), el `curl` a la 5432 y logout, como en los pasos 26 y 28. Luego, en `root@controller:~#` ejecutar:

```bash
sleep 3
su postgres -c "psql -d campus -c \"SELECT id, evento, sesion_id, usuario_id, dispositivo_id, detalle->>'usuario' AS usuario FROM auth_log ORDER BY id DESC LIMIT 8\""
```

Resultado esperado, del más nuevo al más antiguo: `SESION_CERRADA`, `AUTORIZACION_DENEGADA`, `SESION_ACTIVA`, `SESION_AUTENTICADA` y `AUTH_ACEPTADA`; las filas con `sesion_id` tienen `usuario_id` y `dispositivo_id` completados por el disparador, y `AUTH_ACEPTADA` tiene `usuario_id` (resuelto desde el uid).

**Captura F54:** la consulta.

### Paso 30. Reinicio de M6 (M6-7)

En h1, login y logout. Luego, en `root@controller:~#` ejecutar:

```bash
systemctl restart m6-auditoria; sleep 5
su postgres -c "psql -d campus -c \"SELECT ts, evento, count(*) FROM auth_log WHERE ts > now() - interval '10 minutes' GROUP BY ts, evento, detalle::text HAVING count(*) > 1\""
```

Resultado esperado: ninguna fila: ningún evento quedó dos veces con el mismo instante y detalle (el reinicio sigue desde su punto de control). Los eventos del login y logout de este paso aparecen una vez cada uno.

**Captura F55:** la consulta.

## 7 quinquies. Resultados de la validación de M2 y M6 en el slice (13/11/2026)

| Prueba | Resultado | Dato clave |
|---|---|---|
| M2-1, M2-2 | Pasa | Compila con Maven en controller y queda ACTIVE 2.0.0; 21/21 en la Tabla 1 de cada switch (13 permisos y 8 comunes), las 55 con el meter captura y la 57 con el de cuarentena; `tel354:base` intacto (7/7, 7/7, 20/20) |
| M2-3, M2-4 | Pasa | Campus Virtual `200`; la 5432 se deniega con un DROP 150 de 8 s y cookie (M2, 0x02, sesión 52, contador); las retransmisiones del SYN se descartan en el switch (`n_packets=3`) sin nuevos Packet-In |
| M2-5 | Pasa | `policy_changed` → Tabla 1 completa en ambos switches en ≈ 0,73 s (`+2 -0`: Alumno y JP por herencia); el tráfico pasa por la nueva regla 100; la reversión deja 13 permisos |
| M2-7, M2-8 | Pasa | Al cerrar la sesión, `1 reglas de M2 retiradas`; tras `bundle:restart`, Tabla 1 completa sin ningún Flow-Mod |
| M6-1, M6-2 | Pasa | `onos_m6` solo con INSERT (`t\|f`); 14 pruebas OK; servicio activo con el perfil de una instancia |
| M6-3 | Pasa | Ciclo completo en `auth_log`: AUTH_ACEPTADA, SESION_AUTENTICADA, SESION_ACTIVA, AUTORIZACION_DENEGADA y SESION_CERRADA, con `usuario_id` y `dispositivo_id` completados por el disparador |
| M6-7 | Pasa | Tras reiniciar M6, ningún evento duplicado y el ciclo nuevo registrado una vez |

*Tabla 5 sexies. Resultados de M2 y M6 en el slice. Horas en UTC (Lima = UTC−5).*

Observaciones: al detenerse, M2 registraba un aviso por la revisión que interrumpe su propio cierre (corregido en el código: ahora es una línea de depuración); la configuración de M2 se aplica dos veces al arrancar (solo líneas de log repetidas, pendiente 11). Antes de las pruebas, una actualización automática nocturna reinició `systemd-networkd` en varias VMs y deshizo los túneles; se volvieron a montar con el paso 11 bis.

## 7 sexies. Pruebas de M4a y del IDS

M4a (`tel354.m4a`) cuenta, por sesión, los intentos denegados que le pasa M2 en ventanas de 10 s y 600 s, y publica `ThreatSuspected` al llegar a un umbral; M5 decide la contención. El IDS (Suricata) es un complemento pasivo: en el slice escucha la interfaz de datos de controller (`ens4`), por donde llega todo el tráfico hacia los servidores, y detecta lo que el controlador no ve porque el tráfico está autorizado (fuerza bruta contra un servicio y peticiones maliciosas). Su adaptador envía cada alerta a la REST de M4a (`POST /onos/campus-ids/v1/alertas`). No se modifican `tel354-api`, M1, M2, M3, M5, M7 ni M8: solo se instala M4a, el IDS y la versión de M6 que registra sus alertas (`IDS_ALERTA`).

Antes de empezar, comprobar los túneles (paso 11 bis) y cerrar sesión en los cuatro hosts.

| Prueba | Qué verifica | Paso | Captura |
|---|---|---|---|
| A1 | M4a compila, se activa, carga sus umbrales y su REST responde | 31 | F56 |
| A2 | Escaneo de puertos: M2 → M4a (métrica P) → M5 (N1) | 32 | F57, F58 |
| A3 | Suricata y el adaptador instalados; M6 actualizado | 33 | F59 |
| A4 | Petición maliciosa: Suricata → adaptador → M4a (métrica IDS) → M5, y `IDS_ALERTA` en `auth_log` | 34 | F60, F61 |

*Tabla 5 septies. Pruebas de M4a y del IDS en el slice.*

### Paso 31. Instalar M4a (A1)

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
export TEL354_ENV=una_instancia.env
bash r3-ataques-encubiertos/instalar_r3.sh m4a 2>&1 | tee /root/mvn.log
docker logs onos 2>&1 | grep -E "M4a|M5: suscrito" | tail -4
curl -s -u onos:rocks -X POST -H "Content-Type: application/json" http://127.0.0.1:8181/onos/campus-ids/v1/alertas \
  -d '{"src_ip":"10.99.0.1","dst_ip":"10.200.1.10","proto":"tcp","dst_port":443,"sid":1000004,"categoria":"Web Application Attack","firma":"prueba"}'; echo
```

Resultado esperado: `tel354.m4a instalada y activa` y `r3-netcfg.json cargada`; en el log, `M4a iniciado: W1 = 10 s, W2 = 600 s` y `M5: suscrito a ThreatSuspected (M4a)`; la REST responde `{"resultado":"SIN_SESION"}` (la IP 10.99.0.1 no tiene sesión: la alerta se descarta sin efectos). En `onos@root >`, `apps -s | grep tel354` muestra `tel354.m4a` activa.

**Captura F56.**

### Paso 32. Escaneo de puertos (A2)

Ventana de log en `root@controller:~#`:

```bash
docker logs -f --since 1m onos 2>&1 | grep -E "M4a:|M5: incidente|M2: AuthorizationDenied"
```

En h1 ejecutar `bash r1-identidad/host/login.sh vx-sw2` (alumno01) y luego:

```bash
nmap -Pn -sS -p 1-40 --max-rate 20 10.200.1.30
```

En `root@sw2:~#` ejecutar:

```bash
ovs-ofctl -O OpenFlow13 dump-flows $(ovs-vsctl list-br | head -1) table=1 | grep -E "priority=30[0-2]"
```

En `onos@root >` ejecutar `tel354:incidentes --abiertos`.

Resultado esperado: una línea `M2: AuthorizationDenied` por puerto; al décimo puerto distinto, `M4a: ThreatSuspected P=10/10 W=10s sesion N -> [10.200.1.30/6[…]]`; luego `M5: incidente I [alumno01 ESCANEO_PUERTOS Bajo/MAYOR N1, 1 sesiones]`. En sw2, una regla 300 (`drop`) desde la IP de h1 hacia `10.200.1.30`, con `hard_timeout=900`. `--max-rate 20` mantiene el escaneo por debajo del meter de captura (50 pps).

**Captura F57:** h1 y el log. **F58:** el `dump-flows` y `tel354:incidentes`.

Para seguir sin la contención, en `onos@root >` ejecutar `tel354:incidente-descartar I --motivo "prueba A2"` (con el id del incidente).

### Paso 33. Suricata, adaptador y M6 (A3)

En `root@controller:~/tel354-campus-sdn#` ejecutar:

```bash
bash r3-ataques-encubiertos/ids/instalar_ids.sh
bash r3-ataques-encubiertos/m6-auditoria/instalar_m6.sh
```

Resultado esperado: la memoria libre; `This is Suricata version …`; `Configuration provided was successfully loaded`; `suricata` e `ids-adapter` en `active (running)`; `Interfaz: ens4 · Reglas: 3 · Destino: http://127.0.0.1:8181/onos/campus-ids/v1/alertas`. El instalador de M6 conserva `/etc/tel354/m6.env` y reinicia el servicio con la regla `IDS_ALERTA`.

**Captura F59.**

### Paso 34. Petición maliciosa (A4)

Con la ventana de log del paso 32 abierta y h1 con sesión (alumno01), en `root@h1:~#` ejecutar:

```bash
curl -s -o /dev/null -w "%{http_code}\n" "http://10.200.1.10:443/buscar?q=1%20UNION%20SELECT%20usuario,clave"
```

En `root@controller:~#` ejecutar:

```bash
grep '"event_type":"alert"' /var/log/suricata/eve.json | tail -1 | python3 -m json.tool | grep -E '"(signature_id|signature|src_ip|dest_ip|dest_port)"'
journalctl -u ids-adapter -n 3 --no-pager
sleep 3; su postgres -c "psql -P pager=off -d campus -c \"SELECT id, evento, sesion_id, detalle->>'firma_sid' AS sid, detalle->>'tipo' AS tipo FROM auth_log ORDER BY id DESC LIMIT 5\""
```

Resultado esperado: el Campus Virtual responde con un código HTTP, `200` o `404` (la petición pasa: el IDS no bloquea); en `eve.json`, la alerta `1000004` (`TEL354 peticion maliciosa SQLi`) desde la IP de h1; en el adaptador, `OK 200 {"resultado":"ACEPTADA"} 1000004 10.17.x.y`; en el log de ONOS, `M4a: IdsAlert sid 1000004 sev 1 tipo PETICION_MALICIOSA sesion N …`, `M4a: ThreatSuspected IDS=1/1 W=60s …` y `M5: incidente I [alumno01 PETICION_MALICIOSA Fuerte/CRITICO N2 …]` (petición maliciosa es de nivel Fuerte: aislamiento N2). En `auth_log`, `IDS_ALERTA` (sid 1000004, tipo PETICION_MALICIOSA), `SOSPECHA` y `CONTENCION_APLICADA`.

**Captura F60:** h1, `eve.json`, el adaptador y el log. **F61:** la consulta de `auth_log`.

Al terminar, en `onos@root >` ejecutar `tel354:incidente-descartar I --motivo "prueba A4"` y, en h1, `bash r1-identidad/host/logout.sh vx-sw2`.

## 8. Qué enviar si algo falla

| Nivel | Evidencia |
|---|---|
| 1 | `/root/mvn.log` del módulo que falló |
| 2 | Salida de `apps -a -s`, `scr:list` y `docker logs onos 2>&1 \| grep -iE "tel354\|exception" \| tail -60` |
| 3 | La prueba, el resultado obtenido, `docker logs onos 2>&1 \| grep -E "M1:\|M2:\|M5:\|M7\|M8" \| tail -40` y el `dump-flows` del switch; para M6, `journalctl -u m6-auditoria -n 40`; para el IDS, `journalctl -u suricata -u ids-adapter -n 40` |

*Tabla 6. Evidencias para corregir un fallo.*

## 9. Dejar el slice como estaba

En `root@controller:~#` ejecutar:

```bash
docker rm -f onos
sed -i '/alias onos-cli=/d' ~/.bashrc
systemctl disable --now postgresql slapd freeradius kea-dhcp4-server kea-ctrl-agent named 2>/dev/null; systemctl stop tel354-recursos
ip addr flush dev ens4
cd /root/TEL354_LAB5_2026_2/ansible && ansible-playbook slice_reset.yml
```

En cada host, `logout.sh` con su interfaz de datos (`vx-sw2`, `vx-sw3` o `ens4`) y luego `netplan apply` devuelven la interfaz de datos a su IP 10.0.0.x del LAB5.
