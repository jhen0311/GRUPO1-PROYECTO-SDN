#!/usr/bin/env python3
"""
gen_guia_docx.py - Genera docs/GUIA_VALIDACION.docx a partir de docs/VALIDACION.md.

VALIDACION.md es la fuente: el Word no se edita a mano. Pandoc hace la conversión con el Word
anterior como plantilla (estilos, márgenes y pie de página) y este script aplica el formato de
la guía: tablas con cabecera azul y filas alternas, código en Consolas y pies de tabla.

Requisitos: pandoc y python3-lxml.
Uso (desde la raíz del repositorio):
  python3 docs/gen_guia_docx.py                      # reescribe docs/GUIA_VALIDACION.docx
  python3 docs/gen_guia_docx.py --salida /tmp/guia.docx
"""
import argparse, os, re, shutil, subprocess, sys, tempfile, zipfile
from lxml import etree

AQUI = os.path.dirname(os.path.abspath(__file__))
W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
NS = {"w": W}
ANCHO = 9638                       # ancho útil en A4 con márgenes de 2 cm (dxa)
AZUL, GRIS, BORDE = "1F3A5F", "F2F5F9", "A6B4C8"


def q(nombre):
    return "{%s}%s" % (W, nombre)


def el(nombre, **atributos):
    e = etree.Element(q(nombre))
    for k, v in atributos.items():
        e.set(q(k), str(v))
    return e


def rpr(run):
    """rPr del run (lo crea al inicio si no existe)."""
    p = run.find("w:rPr", NS)
    if p is None:
        p = el("rPr")
        run.insert(0, p)
    return p


ORDEN_RPR = ["rStyle", "rFonts", "b", "bCs", "i", "iCs", "color", "sz", "szCs"]   # orden del esquema


def poner(rp, nombre, **atributos):
    """Reemplaza (o agrega) una propiedad del run, respetando el orden del esquema de Word."""
    for viejo in rp.findall("w:" + nombre, NS):
        rp.remove(viejo)
    rp.append(el(nombre, **atributos))
    def pos(e):
        n = etree.QName(e).localname
        return ORDEN_RPR.index(n) if n in ORDEN_RPR else len(ORDEN_RPR)
    rp[:] = sorted(rp, key=pos)


def es_codigo(run):
    return run.find("w:rPr/w:rStyle[@w:val='VerbatimChar']", NS) is not None


def consolas(rp):
    poner(rp, "rFonts", ascii="Consolas", hAnsi="Consolas", cs="Consolas")


def estilo(p):
    s = p.find("w:pPr/w:pStyle", NS)
    return s.get(q("val")) if s is not None else ""


def texto(nodo):
    return "".join(nodo.itertext())


def ppr(p):
    x = p.find("w:pPr", NS)
    if x is None:
        x = el("pPr")
        p.insert(0, x)
    return x


def formato_parrafos(cuerpo):
    primero = True
    for p in cuerpo.iter(q("p")):
        if any(a.tag == q("tc") for a in p.iterancestors()):
            continue                                   # las celdas se tratan con su tabla
        st = estilo(p)
        runs = p.findall("w:r", NS)
        if st == "SourceCode":                         # bloque de comandos
            for r in runs:
                rp = rpr(r)
                if es_codigo(r):
                    consolas(rp)
                poner(rp, "color", val="1F2933")
                poner(rp, "sz", val=17)
            continue
        for r in runs:                                 # código en línea
            if es_codigo(r):
                rp = rpr(r)
                consolas(rp)
                poner(rp, "color", val="8B1A3A")
        if st == "FirstParagraph" and primero:         # línea de versión, bajo el título
            primero = False
            pp = ppr(p)
            bdr = el("pBdr")
            bdr.append(el("bottom", val="single", sz=8, space=6, color=AZUL))
            pp.append(bdr)
            pp.append(el("jc", val="left"))
            for r in runs:
                rp = rpr(r)
                poner(rp, "color", val="404B5A")
                poner(rp, "sz", val=22)
        elif texto(p).startswith("Tabla ") and runs and all(
                r.find("w:rPr/w:i", NS) is not None for r in runs if texto(r).strip()):
            pp = ppr(p)                                # pie de tabla
            pp.append(el("spacing", after=200))
            pp.append(el("jc", val="center"))
            for r in runs:
                rp = rpr(r)
                poner(rp, "color", val="556070")
                poner(rp, "sz", val=18)


def anchos(tabla):
    """Reparte el ancho según el texto más largo de cada columna (entre 8 y 60 caracteres)."""
    filas = tabla.findall("w:tr", NS)
    n = max(len(f.findall("w:tc", NS)) for f in filas)
    largo = [1] * n
    for f in filas:
        for i, c in enumerate(f.findall("w:tc", NS)):
            largo[i] = max(largo[i], len(texto(c)))
    peso = [min(60, max(8, x)) for x in largo]
    a = [max(780, round(ANCHO * x / sum(peso))) for x in peso]
    a[a.index(max(a))] += ANCHO - sum(a)               # el ajuste va a la columna más ancha
    return a


def formato_tabla(tabla):
    a = anchos(tabla)
    pr = tabla.find("w:tblPr", NS)
    for h in list(pr):
        pr.remove(h)
    pr.append(el("tblW", w=ANCHO, type="dxa"))
    pr.append(el("jc", val="center"))
    bordes = el("tblBorders")
    for lado in ("top", "left", "bottom", "right", "insideH", "insideV"):
        bordes.append(el(lado, val="single", sz=4, space=0, color=BORDE))
    pr.append(bordes)
    mar = el("tblCellMar")
    for lado, w in (("top", 40), ("left", 80), ("bottom", 40), ("right", 80)):
        mar.append(el(lado, w=w, type="dxa"))
    pr.append(mar)
    grid = tabla.find("w:tblGrid", NS)
    for h in list(grid):
        grid.remove(h)
    for w in a:
        grid.append(el("gridCol", w=w))

    for nf, fila in enumerate(tabla.findall("w:tr", NS)):
        cabecera = nf == 0
        tp = fila.find("w:trPr", NS)
        if tp is None:
            tp = el("trPr")
            fila.insert(0, tp)
        for h in list(tp):
            tp.remove(h)
        tp.append(el("cantSplit", val="true"))
        if cabecera:
            tp.append(el("tblHeader", val="true"))
        for i, celda in enumerate(fila.findall("w:tc", NS)):
            cp = celda.find("w:tcPr", NS)
            if cp is None:
                cp = el("tcPr")
                celda.insert(0, cp)
            for h in list(cp):
                cp.remove(h)
            cp.append(el("tcW", w=a[i], type="dxa"))
            if cabecera:
                cp.append(el("shd", val="clear", color="auto", fill=AZUL))
            elif nf % 2 == 0:
                cp.append(el("shd", val="clear", color="auto", fill=GRIS))
            for p in celda.findall("w:p", NS):
                pp = ppr(p)
                for nombre in ("spacing", "jc"):
                    for viejo in pp.findall("w:" + nombre, NS):
                        pp.remove(viejo)
                pp.append(el("spacing", before=20, after=20))
                pp.append(el("jc", val="left"))
                for r in p.findall("w:r", NS):
                    rp = rpr(r)
                    if es_codigo(r):
                        consolas(rp)
                        for t in r.findall("w:t", NS):         # en la tabla de Markdown, | va como \|
                            t.text = (t.text or "").replace("\\|", "|")
                    poner(rp, "sz", val=18)
                    if cabecera:
                        poner(rp, "b")
                        poner(rp, "color", val="FFFFFF")
                    elif es_codigo(r):
                        poner(rp, "color", val="8B1A3A")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fuente", default=os.path.join(AQUI, "VALIDACION.md"))
    ap.add_argument("--plantilla", default=os.path.join(AQUI, "GUIA_VALIDACION.docx"),
                    help="Word del que se toman los estilos y el pie de página")
    ap.add_argument("--salida", default=os.path.join(AQUI, "GUIA_VALIDACION.docx"))
    a = ap.parse_args()
    if not shutil.which("pandoc"):
        sys.exit("Falta pandoc (apt install pandoc)")

    with tempfile.TemporaryDirectory() as tmp:
        plantilla = os.path.join(tmp, "plantilla.docx")
        shutil.copy(a.plantilla, plantilla)            # la salida puede ser la misma plantilla
        crudo = os.path.join(tmp, "crudo.docx")
        subprocess.run(["pandoc", a.fuente, "-f", "markdown", "--shift-heading-level-by=-1",
                        "--no-highlight", "--reference-doc=" + plantilla, "-o", crudo], check=True)
        final = os.path.join(tmp, "final.docx")
        with zipfile.ZipFile(crudo) as zin, zipfile.ZipFile(final, "w", zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                if item.filename.endswith("/"):
                    continue
                datos = zin.read(item.filename)
                # Tres salidas de pandoc 3.1 que no cumplen el esquema de Word y se corrigen aquí
                if item.filename == "word/settings.xml":
                    datos = zipfile.ZipFile(plantilla).read(item.filename)      # orden de elementos
                elif item.filename == "word/numbering.xml":
                    datos = re.sub(rb'(<w:nsid w:val=")([0-9A-Fa-f]{1,7})(")',
                                   lambda m: m.group(1) + m.group(2).rjust(8, b"0") + m.group(3), datos)
                if item.filename == "word/document.xml":
                    datos = datos.replace(b'<m:nor /><m:sty m:val="p" />', b"<m:nor />")
                    datos = datos.replace(b'<m:nor/><m:sty m:val="p"/>', b"<m:nor/>")
                    raiz = etree.fromstring(datos)
                    cuerpo = raiz.find("w:body", NS)
                    formato_parrafos(cuerpo)
                    for t in cuerpo.iter(q("tbl")):
                        formato_tabla(t)
                    datos = etree.tostring(raiz, xml_declaration=True, encoding="UTF-8", standalone=True)
                zout.writestr(item, datos)
        shutil.copy(final, a.salida)
    print("Generado", a.salida)


if __name__ == "__main__":
    main()
