# R2 · Acceso a recursos privilegiados solo para usuarios autorizados

R2 es proactivo: la matriz de autorización por rol (`v_permiso_efectivo`) se instala como reglas 100 en la Tabla 1 de cada switch de acceso antes de que llegue cualquier usuario. Por eso el número de reglas de R2 no crece con los usuarios. El acceso a los servidores de cursos es por inscripción: M1 instala una regla 110 por servidor al activar cada sesión (`r1-identidad`).

| Archivo | Función |
|---|---|
| `proactivo/onos_static.py` | Régimen proactivo por la API REST de ONOS: core, Tablas 0, 1 y 2 de acceso y meters. Hace el papel de M2, M7 y M8 hasta que existan como apps. |
| `tests/export_matriz.py` | Exporta la matriz a `/tmp/matriz.json` (los hosts no leen la BD) |
| `tests/bateria_r2.py` | Desde un host con sesión: intenta cada recurso y mide precisión y alcance |
| `tests/contar_flows.py` | Compara las flow entries con el modelo del AVZ08: F_acc = c0 + P + 5S + C |

```bash
python3 r2-autorizacion/proactivo/onos_static.py           # instalar (antes de M1)
python3 r2-autorizacion/proactivo/onos_static.py --borrar  # retirar
python3 r2-autorizacion/tests/export_matriz.py > /tmp/matriz.json
# en la shell de h1 (entrar.sh h1):
python3 r2-autorizacion/tests/bateria_r2.py --usuario alumno.demo
```

`--usuario` solo indica qué resultado se espera. Las reglas que deciden son las de la sesión real del host: si se declara otro usuario, la batería muestra fallas, porque el control está en el plano de datos.

Pendiente: M2 como app de ONOS (DROP 150 en el slow-path de la regla 55 y recálculo con `policy_changed`).
