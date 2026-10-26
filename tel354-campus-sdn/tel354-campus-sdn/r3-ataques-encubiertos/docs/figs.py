#!/usr/bin/env python3
"""Figuras del LLSD de R3 con el estilo de las del Avance 07."""
import subprocess
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch

NAVY = "#1F3A5F"; FILL = "#DCE6F2"; BLUE = "#2F5597"; GREEN = "#4E7D2E"; RED = "#C00000"; GRAY = "#F2F2F2"

# ---------------- Figura 3: secuencia ----------------
def secuencia(path):
    parts = ["Host\n(alumna)", "SW acceso\n(OVS)", "M2", "M4a", "M5", "M6", "PostgreSQL"]
    pasos = [
        ("sec", "Escaneo de puertos hacia 10.200.3.10 (BD académica, MAYOR)"),
        ("msg", 0, 1, "SYN :21, :22, :23 … (sin permiso)", "s"),
        ("msg", 1, 2, "Packet-In (regla 55, meter captura)", "s"),
        ("self", 2, "getSessionByIp → sesión; matriz: DENY"),
        ("msg", 2, 1, "Flow-Mod DROP 150 (cuádrupla, 8 s, cookie 0x02)", "s"),
        ("msg", 2, 3, "AuthorizationDenied (d, proto, puerto)", "s"),
        ("self", 3, "P(s, d, W₁) = 10 ≥ θ_P1"),
        ("sec", "Evaluación y contención"),
        ("msg", 3, 4, "ThreatSuspected (P, destinos, ts)", "s"),
        ("msg", 4, 6, "reincidencia (24 h)", "s"),
        ("msg", 6, 4, "0 incidentes previos", "r"),
        ("self", 4, "Bajo × MAYOR → N1; reserva: 1 entrada"),
        ("msg", 4, 6, "INSERT incidente … RETURNING id", "s"),
        ("msg", 6, 4, "id", "r"),
        ("msg", 4, 1, "Flow-Mod 300: ipv4_src=IP → 10.200.3.10 drop (900 s, cookie 0x06)", "s"),
        ("msg", 4, 5, "ContainmentApplied (N1)", "s"),
        ("msg", 5, 6, "INSERT auth_log (sospecha y contención)", "s"),
        ("sec", "Después de la contención"),
        ("msg", 0, 1, "más sondas hacia 10.200.3.10", "s"),
        ("self", 1, "regla 300: descarte en el switch, sin Packet-In"),
        ("msg", 0, 1, "HTTPS al Campus Virtual: 520 → 100 → Tabla 2 (sin cambio)", "s"),
    ]
    W, gap = 1.65, 0.62
    n = len(parts)
    H = 1.2 + gap * (len(pasos) + 1)
    fig, ax = plt.subplots(figsize=(W * n * 0.98, H * 0.92), dpi=170)
    ax.set_xlim(-0.2, W * (n - 1) + 1.4); ax.set_ylim(-H, 0.9); ax.axis("off")
    xs = [0.6 + i * W for i in range(n)]
    for x, p in zip(xs, parts):
        ax.add_patch(FancyBboxPatch((x - 0.62, 0.05), 1.24, 0.72, boxstyle="round,pad=0.02,rounding_size=0.05",
                                    fc=FILL, ec=NAVY, lw=1.4))
        ax.text(x, 0.41, p, ha="center", va="center", fontsize=9.5, weight="bold", color=NAVY)
        ax.plot([x, x], [0.05, -H + 0.2], ls=(0, (4, 3)), color="#777", lw=1)
    y = -0.45
    for st in pasos:
        if st[0] == "sec":
            ax.add_patch(plt.Rectangle((-0.1, y - 0.2), W * (n - 1) + 1.4, 0.36, fc=GRAY, ec="#BBB", lw=1))
            ax.text((W * (n - 1) + 1.2) / 2, y - 0.02, st[1], ha="center", va="center", fontsize=9.5, weight="bold", color="#333")
        elif st[0] == "msg":
            _, a, b, txt, kind = st
            col = BLUE if kind == "s" else GREEN
            ls = "-" if kind == "s" else (0, (5, 3))
            ax.annotate("", xy=(xs[b] + (-0.05 if b > a else 0.05), y - 0.12), xytext=(xs[a], y - 0.12),
                        arrowprops=dict(arrowstyle="-|>", color=col, lw=1.4, ls=ls, mutation_scale=11))
            ax.text((xs[a] + xs[b]) / 2, y - 0.02, txt, ha="center", va="bottom", fontsize=8.6, color="black")
        else:
            _, a, txt = st
            ax.annotate("", xy=(xs[a] + 0.05, y - 0.16), xytext=(xs[a] + 0.05, y + 0.02),
                        arrowprops=dict(arrowstyle="-|>", color=RED, lw=1.2, connectionstyle="arc3,rad=-0.9", mutation_scale=9))
            ax.text(xs[a] + 0.2, y - 0.07, txt, ha="left", va="center", fontsize=8.6, color=RED)
        y -= gap
    fig.savefig(path, bbox_inches="tight", facecolor="white")

# ---------------- Figura 1: módulos (graphviz) ----------------
DOT_MODULOS = r'''
digraph G {
  graph [rankdir=LR, fontname="DejaVu Sans", fontsize=11, nodesep=0.35, ranksep=0.7, bgcolor=white, pad=0.2];
  node  [shape=box, style="rounded,filled", fillcolor="#DCE6F2", color="#1F3A5F", penwidth=1.4,
         fontname="DejaVu Sans", fontsize=10, fontcolor="#1F3A5F"];
  edge  [fontname="DejaVu Sans", fontsize=8.5, color="#2F5597", fontcolor="#222222", penwidth=1.2, arrowsize=0.7];

  subgraph cluster_dp { label="Switch de acceso (OVS)"; style="rounded,filled"; fillcolor="#F2F2F2"; color="#999999"; fontcolor="#333333";
    r800 [label="800 · ARP\nmeter ctl_P", fillcolor=white];
    r505 [label="505 · IP incoherente\nmeter ctl_P", fillcolor=white];
    r55  [label="55 · captura\nmeter 50 pps", fillcolor=white];
    r150 [label="150 · DROP 8 s", fillcolor=white];
    r300 [label="300–302 · contención\n(reserva 96)", fillcolor=white];
  }
  subgraph cluster_onos { label="ONOS 1.13 · aplicaciones TEL354 (tel354-api: eventos y servicios)"; style="rounded"; color="#1F3A5F"; fontcolor="#1F3A5F";
    m1  [label="M1 (+ mínimo de M8)\nsesiones · proxy ARP"];
    m2  [label="M2\nslow-path"];
    m3  [label="M3\ncontadores 5 s"];
    m4a [label="M4a\nventanas W₁ / W₂"];
    m5  [label="M5\nevaluación · contención"];
    m6  [label="M6\nauditoría"];
  }
  db  [label="PostgreSQL\nincidente · cuarentena\nauth_log", shape=cylinder, fillcolor="#FFFFFF"];
  ti  [label="TI\nAPI REST · consola", shape=box, fillcolor="#FFF8E6", color="#B8860B", fontcolor="#7A5A00"];

  r800 -> m1 [label="Packet-In"];
  r505 -> m1 [label="Packet-In"];
  r55  -> m2 [label="Packet-In"];
  m2 -> r150 [label="Flow-Mod", style=dashed];
  m1 -> m4a [label="BindingViolation\nArpProbe"];
  m2 -> m4a [label="AuthorizationDenied"];
  m4a -> m5 [label="ThreatSuspected"];
  m5 -> r300 [label="Flow-Mod / Meter-Mod", style=dashed];
  r300 -> m3 [label="estadísticas", style=dotted];
  m3 -> m5 [label="CounterDelta"];
  m1 -> m5 [label="SessionReady\nSessionClosed"];
  m5 -> m1 [label="QuarantineConfirmed"];
  m5 -> db [label="JDBC/TLS"];
  m5 -> m6 [label="Containment*"];
  m6 -> db [label="INSERT"];
  ti -> m5 [label="IncidentAdminService"];
}
'''

DOT_ESTADOS = r'''
digraph S {
  graph [rankdir=LR, fontname="DejaVu Sans", fontsize=11, nodesep=0.4, ranksep=0.55, bgcolor=white, pad=0.2];
  node  [shape=box, style="rounded,filled", fillcolor="#DCE6F2", color="#1F3A5F", penwidth=1.4,
         fontname="DejaVu Sans", fontsize=10, fontcolor="#1F3A5F"];
  edge  [fontname="DejaVu Sans", fontsize=8.5, color="#2F5597", penwidth=1.2, arrowsize=0.7];
  ini [shape=point, width=0.15, color="#1F3A5F"];
  subgraph cluster_inv { label="EN_INVESTIGACION (mitigado_en nulo, sin cuarentena)"; labelloc="b"; style="rounded"; color="#1F3A5F"; fontcolor="#1F3A5F";
    n0 [label="N0 · observación\nsin reglas"];
    n1 [label="N1 · restricción puntual\n1 regla 300 · 900 s"];
    n2 [label="N2 · restricción amplia\n4 reglas por sesión · 3 600 s"];
  }
  conf [label="CONFIRMADO\nN3 · cuarentena", fillcolor="#F8D7DA", color="#C00000", fontcolor="#7A0000"];
  desc [label="DESCARTADO\nmitigado_en", fillcolor="#E2EFDA", color="#4E7D2E", fontcolor="#2E5A14"];
  ini -> n0 [label="ThreatSuspected"];
  ini -> n1 [label="según la matriz"]; ini -> n2;
  n0 -> n1 [label="nueva evidencia\no reincidencia"];
  n1 -> n2 [label="reincidencia o\nvarios destinos"];
  n1 -> n1 [label="renovación"];
  n2 -> n2 [label="CounterDelta 301\no evidencia"];
  n0 -> desc [label="cierre o\ndescarte de TI"];
  n1 -> desc [label="vence sin\nrenovar / TI"];
  n2 -> desc [label="vence sin\nrenovar / TI"];
  n1 -> conf [label="TI confirma"];
  n2 -> conf [label="TI confirma, inequívoca\no reincidencia grave"];
}
'''

if __name__ == "__main__":
    secuencia("fig2_secuencia.png")
    for nombre, dot in (("fig1_modulos", DOT_MODULOS), ("fig3_estados", DOT_ESTADOS)):
        open(nombre + ".dot", "w").write(dot)
        subprocess.run(["dot", "-Tpng", "-Gdpi=170", nombre + ".dot", "-o", nombre + ".png"], check=True)
    print("ok")
