import re, subprocess, sys, xml.etree.ElementTree as ET
xml, target = sys.argv[1], sys.argv[2].lower()
nodes = [(n.get('text') or '', n.get('bounds')) for n in ET.parse(xml).iter('node')]
texts = [t for t, _ in nodes if t.strip()]
print('SCREEN TEXTS:', texts[:60])
if target == '-':
    sys.exit(0)
for t, b in nodes:
    if target in t.lower():
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', b))
        print('TAP', repr(t), (x1 + x2) // 2, (y1 + y2) // 2)
        subprocess.run(['adb', 'shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2)])
        sys.exit(0)
print('NOT FOUND:', target)
