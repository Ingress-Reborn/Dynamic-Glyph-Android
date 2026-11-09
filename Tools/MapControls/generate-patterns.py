"""Generate sparse control patterns from individually cropped map controls.

Input is the original map frame, not a composite of the three right-side icons.
The generated Kotlin file contains only sampled icon geometry; no map or text.
"""
from pathlib import Path
import argparse
import math
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument('frame', type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
frame = Image.open(args.frame).convert('RGB')
if frame.size != (1216,2688):
    raise ValueError('Calibration coordinates refer to the 1216x2688 source map frame')
controls = [('DIAMOND',1073,580,118),('SIGNAL',1073,752,118),
            ('LAYERS',1073,920,118),('PLUS',94,674,82),('BRIEFCASE',1073,2307,118)]

def components(points):
    remaining = set(points)
    result = []
    while remaining:
        stack = [remaining.pop()]
        found = []
        while stack:
            x,y = stack.pop()
            found.append((x,y))
            for dy in (-1,0,1):
                for dx in (-1,0,1):
                    point = x+dx,y+dy
                    if point in remaining:
                        remaining.remove(point)
                        stack.append(point)
        result.append(found)
    return result

def spread(points, count):
    points = sorted(points, key=lambda point:(point[1],point[0]))
    return [points[round(i*(len(points)-1)/(min(count,len(points))-1))]
            for i in range(min(count,len(points)))]

def packed(points):
    return ', '.join(str(y*32+x) for x,y in points)

entries = []
for kind,cx,cy,size in controls:
    crop = frame.crop((cx-size//2,cy-size//2,cx+size//2,cy+size//2))
    # The test fixture is a separate full-colour raster, rather than samples
    # reconstructed from the detector's sparse pattern.
    test = crop.resize((96,96),Image.Resampling.LANCZOS)
    pixels = test.load()
    for y in range(96):
        for x in range(96):
            if (x-47.5)**2+(y-47.5)**2 > 46**2:
                pixels[x,y]=(0,0,0)
    test.save(root/'recognition/src/test/resources/map-controls'/f'{kind.lower()}.png')
    small = crop.resize((32,32),Image.Resampling.LANCZOS)
    for rotation in ([step * 22.5 for step in range(16)] if kind=='DIAMOND' else (0,)):
        rotated = small.rotate(rotation,resample=Image.Resampling.BICUBIC,fillcolor=(0,0,0))
        bright = {(x,y) for y in range(32) for x in range(32)
                  if (x-15.5)**2+(y-15.5)**2<=14**2 and max(rotated.getpixel((x,y)))>=125}
        dark = {(x,y) for y in range(32) for x in range(32)
                if (x-15.5)**2+(y-15.5)**2<=14**2 and max(rotated.getpixel((x,y)))<=40
                and all((x+dx,y+dy) not in bright for dx in (-1,0,1) for dy in (-1,0,1))}
        groups = components(bright)
        if kind=='PLUS':
            anchor = min(groups,key=lambda points:min((x-15.5)**2+(y-15.5)**2 for x,y in points))
        else:
            anchor = max(groups,key=len)
        left,top = min(x for x,y in anchor),min(y for x,y in anchor)
        width,height = max(x for x,y in anchor)-left+1,max(y for x,y in anchor)-top+1
        negative = spread(dark,48)
        positive = spread(bright,36)
        gaps = []
        required = []
        grey = []
        if kind=='DIAMOND':
            grey = spread({(x,y) for y in range(32) for x in range(32)
                           if (x-15.5)**2+(y-15.5)**2<=9**2
                           and 45<=max(rotated.getpixel((x,y)))<=110
                           and max(rotated.getpixel((x,y)))-min(rotated.getpixel((x,y)))<=20},8)
        if kind=='SIGNAL':
            # Both arcs and the body must contribute evidence independently.
            positive = (spread({(x,y) for x,y in bright if y<12},12) +
                        spread({(x,y) for x,y in bright if 12<=y<16},12) +
                        spread({(x,y) for x,y in bright if y>=16},12))
            gaps = spread({(x,y) for x,y in dark if 12<=x<=20 and 10<=y<=15},12)
        identity = set()
        if kind=='LAYERS':
            identity = {(x,y) for x,y in bright if y>=top+height}
            gaps = spread({(x,y) for x,y in dark if 12<=x<=19 and 17<=y<=22},12)
            required = spread({(x,y) for x,y in bright if y>=21},8)
        elif kind=='PLUS':
            identity = set(anchor)
        elif kind=='BRIEFCASE':
            identity = {(x,y) for x,y in bright if y<top}
            gaps = [(x,y) for y in (9,10) for x in range(14,18)
                    if max(rotated.getpixel((x,y)))<=40]
        if identity:
            # A filled diamond, empty box or plain rectangle must not pass by
            # spending the whole miss allowance on the icon's defining detail.
            positive = spread(bright-identity,24) + spread(identity,12)
        anchors = [(left,top,width,height)]
        if kind != 'DIAMOND':
            fullLeft,fullTop = min(x for x,y in bright),min(y for x,y in bright)
            fullWidth,fullHeight = max(x for x,y in bright)-fullLeft+1,max(y for x,y in bright)-fullTop+1
            # Thin gaps can join at a smaller viewport; both connected extents
            # describe this same icon, with exactly the same shape evidence.
            if (fullLeft,fullTop,fullWidth,fullHeight) not in anchors:
                anchors.append((fullLeft,fullTop,fullWidth,fullHeight))
        for anchorLeft,anchorTop,anchorWidth,anchorHeight in anchors:
            entries.append(f'        Pattern(MapControlReader.Kind.{kind}, {anchorLeft}, {anchorTop}, {anchorWidth}, {anchorHeight},\n'
                           f'            intArrayOf({packed(positive)}),\n'
                           f'            intArrayOf({packed(negative)}),\n'
                           f'            intArrayOf({packed(gaps)}),\n'
                           f'            intArrayOf({packed(required)}),\n'
                           f'            intArrayOf({packed(grey)})),')
output = root/'recognition/src/main/kotlin/org/szres/glyphlens/recognition/image/MapControlPatterns.kt'
output.write_text('''// Generated by Tools/MapControls/generate-patterns.py; regenerate from the source frame.
package org.szres.glyphlens.recognition.image

internal object MapControlPatterns {
    data class Pattern(
        val kind: MapControlReader.Kind,
        val anchorLeft: Int,
        val anchorTop: Int,
        val anchorWidth: Int,
        val anchorHeight: Int,
        val bright: IntArray,
        val dark: IntArray,
        val gaps: IntArray,
        val required: IntArray,
        val grey: IntArray,
    )
    val all = arrayOf(
'''+ '\n'.join(entries)+ '\n    )\n}\n',encoding='utf-8')
print(f'Generated {len(entries)} independent patterns and five colour fixtures')
