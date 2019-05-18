#!/usr/bin/python
 
import sys, os
from oasa.cairo_out import cairo_out
from oasa.smiles import text_to_mol
from oasa.coords_generator import coords_generator
 
 
filenames = sys.argv[1:]
 
all_pngs = []
for smi_filename in filenames:
    if not smi_filename.endswith(".smi"):
        print "Skipping %s. Has not .smi extension." % smi_filename
        continue
    f = file(smi_filename)
    text = f.readline().split()[0]
    f.close()
    print text
    mol = text_to_mol(text)
    cg = coords_generator(35)
    cg.calculate_coords(mol, force=1)
    png_filename = smi_filename.replace(".smi", ".png")
    co = cairo_out()
    co.mol_to_cairo(mol, png_filename)
    all_pngs.append(png_filename)
 
# write some html
f = file("overview.html", "w")
print >> f, """<html><head></head><body><style>
div.figure {
  border: thin silver solid;
  margin: 0.5em;
  padding: 0.5em;
  display: inline-block;
}
div.figure p {
  text-align: center;
  font-style: italic;
  font-size: smaller;
  text-indent: 0;
}
</style>
"""
 
for png_filename in all_pngs:
    print >> f, "<div class='figure'><p><img src='%(file)s' /></p><p>%(label)s</p></div>" % {
        'file': png_filename,
        'label': png_filename,
#        'label': png_filename[2:].split("/")[-2],
    }
print >> f, "</body>"
f.close()

