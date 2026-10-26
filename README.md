<div align="center">

# Red de campus SDN · Zero Trust

### Arquitectura de red de campus universitario basada en SDN/OpenFlow, con autenticación 802.1X y control de acceso dinámico por rol

**TEL354 · Redes Definidas por Software**<br>
Pontificia Universidad Católica del Perú · 2026-2 · **Grupo 01**

<br>

[![ONOS](https://img.shields.io/badge/ONOS-1.13.0-0B5394?style=for-the-badge)](https://opennetworking.org/onos/)
[![OpenFlow](https://img.shields.io/badge/OpenFlow-1.3-2E7D32?style=for-the-badge)](https://opennetworking.org/)
[![Open vSwitch](https://img.shields.io/badge/Open%20vSwitch-2.13-455A64?style=for-the-badge)](https://www.openvswitch.org/)
[![Mininet](https://img.shields.io/badge/Mininet-2.3.0-F57C00?style=for-the-badge)](http://mininet.org/)

</div>

---

## Contexto

En una red de campus tradicional, cualquier equipo conectado a una toma de red recibe conectividad y puede alcanzar los servidores internos: la red **confía por ubicación**. En un campus con miles de usuarios (alumnos, docentes, personal administrativo, TI e invitados), ese modelo deja expuestos los recursos académicos y administrativos.

Este proyecto propone una red de campus basada en **SDN (Software-Defined Networking)** con un enfoque **Zero Trust**: ningún equipo recibe acceso por estar conectado; cada usuario debe **autenticarse** y la red le concede **solo lo que su rol permite**.

## Objetivo

Diseñar e implementar una arquitectura de red de campus en la que un controlador SDN (**ONOS**) programe dinámicamente los switches (**Open vSwitch**, vía **OpenFlow 1.3**) según la identidad y el rol de cada usuario. La arquitectura cumple cinco requerimientos de seguridad:

| | Requerimiento |
|:-:|---|
| **R1** | Identidad y sesión: autenticación 802.1X y asignación de IP según rol |
| **R2** | Autorización: acceso a recursos según rol y cursos inscritos |
| **R3** | Detección y mitigación de ataques encubiertos de usuarios autenticados |
| **R4** | Detección y mitigación de ataques DDoS |
| **R5** | Seguridad perimetral |

R1 y R2 establecen quién entra a la red y a qué puede acceder. R3, R4 y R5 abordan las **amenazas contra la intranet** que persisten aun con ese control, y que la arquitectura debe detectar y mitigar:

- **R3 · Ataques encubiertos:** un usuario que ya se autenticó puede abusar de su acceso, por ejemplo escaneando la red en busca de servicios o suplantando la identidad de otro equipo. El controlador detecta estos comportamientos y contiene al usuario de forma escalonada.
- **R4 · DDoS:** inundaciones de tráfico desde uno o varios equipos internos para saturar un servidor o el propio controlador. Se detectan por volumen y se limitan en el switch.
- **R5 · Seguridad perimetral:** amenazas que entran o salen por el borde hacia Internet. El switch edge actúa como firewall, apoyado por un IDS.

## Cómo funciona

Cuando un usuario conecta su equipo, el puerto del switch **no tiene red**. El equipo se autentica con **802.1X** y el controlador ONOS valida sus credenciales contra el servidor de autenticación, del que obtiene su **rol**. Con la sesión aceptada, el usuario recibe una IP por DHCP y ONOS instala en el switch de acceso **solo las reglas OpenFlow que su rol permite**. Al cerrar la sesión o vencer su tiempo, esas reglas se retiran y el puerto vuelve a quedar sin acceso.

## Entorno de pruebas

La arquitectura se valida sobre un **slice del VNRT**, el testbed del laboratorio, que sirve como maqueta de la red de campus: un switch core, dos switches de acceso que representan dos pabellones y cuatro hosts de usuario, cada uno en su propia VM con Open vSwitch o Linux, más el controlador ONOS en una VM aparte. La misma topología se replica en **Mininet** para desarrollar y probar los módulos antes de llevarlos al slice.

## Tecnologías

| Área | Tecnología |
|---|---|
| Controlador SDN | ONOS 1.13 (apps propias en Java/OSGi) |
| Plano de datos | Open vSwitch · OpenFlow 1.3 |
| Pruebas | Slice del VNRT · Mininet 2.3 |
| Autenticación | IEEE 802.1X · EAP-TTLS · FreeRADIUS · OpenLDAP |
| Datos y direccionamiento | PostgreSQL · ISC Kea DHCP |
| Scripts | Python 3 · Bash |

<div align="center">
<br>
<sub>Proyecto académico · TEL354 · PUCP 2026-2</sub>
</div>
