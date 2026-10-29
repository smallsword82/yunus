"""Rewrite a Draco-compressed .glb as plain (decoder-free) geometry.

The head unit spends ~5 s decoding the Draco meshes of dolphin_parts.glb in its WebView. This
decodes them once on the PC and writes positions/UVs as float32 and normals as normalized int8
(KHR_mesh_quantization, read natively by three.js), so the car only has to read the file.

    pip install DracoPy numpy
    python tools/undraco.py viewer/dolphin_parts.glb viewer/dolphin_parts.glb
"""
import json
import struct
import sys

import DracoPy
import numpy as np

src, dst = sys.argv[1], sys.argv[2]
data = open(src, 'rb').read()
jlen = struct.unpack('<I', data[12:16])[0]
gltf = json.loads(data[20:20 + jlen])
o = 20 + jlen
blen = struct.unpack('<I', data[o:o + 4])[0]
binb = data[o + 8:o + 8 + blen]

views, accessors = gltf['bufferViews'], gltf['accessors']
out = bytearray()
new_views = []


def add_view(raw, target=None, stride=None):
    while len(out) % 4:
        out.append(0)
    v = {'buffer': 0, 'byteOffset': len(out), 'byteLength': len(raw)}
    if target:
        v['target'] = target
    if stride:
        v['byteStride'] = stride
    out.extend(raw)
    new_views.append(v)
    return len(new_views) - 1


def add_accessor(view, ctype, count, atype, normalized=False, lo=None, hi=None):
    a = {'bufferView': view, 'componentType': ctype, 'count': count, 'type': atype}
    if normalized:
        a['normalized'] = True
    if lo is not None:
        a['min'], a['max'] = lo, hi
    accessors.append(a)
    return len(accessors) - 1


# Keep every non-geometry view (images) with a new index.
draco_views = {p['extensions']['KHR_draco_mesh_compression']['bufferView']
               for m in gltf['meshes'] for p in m['primitives']}
remap = {}
for i, v in enumerate(views):
    if i in draco_views:
        continue
    s = v.get('byteOffset', 0)
    remap[i] = add_view(binb[s:s + v['byteLength']], v.get('target'), v.get('byteStride'))
for img in gltf.get('images', []):
    img['bufferView'] = remap[img['bufferView']]

ARRAY, ELEMENTS = 34962, 34963
for mesh in gltf['meshes']:
    for p in mesh['primitives']:
        ext = p['extensions'].pop('KHR_draco_mesh_compression')
        if not p['extensions']:
            del p['extensions']
        v = views[ext['bufferView']]
        s = v.get('byteOffset', 0)
        dm = DracoPy.decode(binb[s:s + v['byteLength']])
        for name, uid in ext['attributes'].items():
            arr = np.asarray(dm.get_attribute_by_unique_id(uid)['data'], dtype=np.float32)
            n = len(arr)
            if name == 'NORMAL':
                q = np.clip(np.round(arr * 127), -127, 127).astype(np.int8)
                q = np.hstack([q, np.zeros((n, 1), np.int8)])  # pad each normal to 4 bytes
                p['attributes'][name] = add_accessor(add_view(q.tobytes(), ARRAY, 4), 5120, n, 'VEC3', True)
            else:
                width = arr.shape[1]
                lo = hi = None
                if name == 'POSITION':
                    lo, hi = arr.min(0).tolist(), arr.max(0).tolist()
                p['attributes'][name] = add_accessor(add_view(arr.tobytes(), ARRAY), 5126, n,
                                                     'VEC%d' % width, lo=lo, hi=hi)
        idx = np.asarray(dm.faces).reshape(-1)
        small = idx.max() < 65536
        idx = idx.astype(np.uint16 if small else np.uint32)
        p['indices'] = add_accessor(add_view(idx.tobytes(), ELEMENTS), 5123 if small else 5125, len(idx), 'SCALAR')

# Drop the accessors that only described Draco data (no bufferView) and renumber the rest.
used = sorted({a for m in gltf['meshes'] for p in m['primitives'] for a in [*p['attributes'].values(), p['indices']]}
              | {a for s in gltf.get('skins', []) for a in [s.get('inverseBindMatrices')] if a is not None}
              | {c[k] for an in gltf.get('animations', []) for c in an['samplers'] for k in ('input', 'output')})
amap = {old: new for new, old in enumerate(used)}
gltf['accessors'] = [accessors[i] for i in used]
for m in gltf['meshes']:
    for p in m['primitives']:
        p['attributes'] = {k: amap[a] for k, a in p['attributes'].items()}
        p['indices'] = amap[p['indices']]

gltf['bufferViews'] = new_views
gltf['buffers'] = [{'byteLength': len(out)}]
for key in ('extensionsUsed', 'extensionsRequired'):
    exts = [e for e in gltf.get(key, []) if e != 'KHR_draco_mesh_compression'] + ['KHR_mesh_quantization']
    gltf[key] = exts

js = json.dumps(gltf, separators=(',', ':')).encode()
js += b' ' * (-len(js) % 4)
out += b'\0' * (-len(out) % 4)
glb = struct.pack('<III', 0x46546C67, 2, 12 + 8 + len(js) + 8 + len(out))
glb += struct.pack('<II', len(js), 0x4E4F534A) + js + struct.pack('<II', len(out), 0x004E4942) + bytes(out)
open(dst, 'wb').write(glb)
print('wrote', dst, len(glb) // 1024, 'KB')
