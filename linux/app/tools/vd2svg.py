#!/usr/bin/env python3
"""Android vector drawables -> GTK symbolic SVGs (the colour comes from CSS).
   vd2svg.py <res/drawable dir> <out dir> name=out-name ..."""
import sys, xml.etree.ElementTree as ET
A = '{http://schemas.android.com/apk/res/android}'

def paths(el, out):
    for c in el:
        tag = c.tag.split('}')[-1]
        if tag == 'path':
            out.append('<path d="%s"/>' % c.get(A + 'pathData'))
        elif tag == 'group':
            inner = []
            paths(c, inner)
            tx, ty = c.get(A + 'translateX', '0'), c.get(A + 'translateY', '0')
            sx, sy = c.get(A + 'scaleX', '1'), c.get(A + 'scaleY', '1')
            out.append('<g transform="translate(%s %s) scale(%s %s)">%s</g>' % (tx, ty, sx, sy, ''.join(inner)))

src, dst = sys.argv[1], sys.argv[2]
for arg in sys.argv[3:]:
    name, out = arg.split('=')
    root = ET.parse('%s/%s.xml' % (src, name)).getroot()
    w, h = root.get(A + 'viewportWidth'), root.get(A + 'viewportHeight')
    body = []
    paths(root, body)
    with open('%s/%s-symbolic.svg' % (dst, out), 'w') as f:
        f.write('<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 %s %s">'
                '<g fill="#000">%s</g></svg>\n' % (w, h, ''.join(body)))
