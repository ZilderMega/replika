"""Add standard PowerPoint fade transitions without changing slide content."""
from pathlib import Path
import re
import sys
import zipfile
import xml.etree.ElementTree as ET

P='http://schemas.openxmlformats.org/presentationml/2006/main'
ET.register_namespace('p',P)
ET.register_namespace('a','http://schemas.openxmlformats.org/drawingml/2006/main')
ET.register_namespace('r','http://schemas.openxmlformats.org/officeDocument/2006/relationships')

def main():
    source, output = map(Path,sys.argv[1:])
    assert source.resolve() != output.resolve()
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(output,'w',compression=zipfile.ZIP_DEFLATED) as result:
        for entry in original.infolist():
            data=original.read(entry.filename)
            if re.fullmatch(r'ppt/slides/slide\d+\.xml',entry.filename):
                root=ET.fromstring(data)
                for child in list(root):
                    if child.tag==f'{{{P}}}transition': root.remove(child)
                trans=ET.Element(f'{{{P}}}transition',{'spd':'slow','advClick':'1'})
                ET.SubElement(trans,f'{{{P}}}fade')
                insertion=next((i for i,e in enumerate(root) if e.tag in (f'{{{P}}}timing',f'{{{P}}}extLst')),len(root))
                root.insert(insertion,trans)
                data=ET.tostring(root,encoding='utf-8',xml_declaration=True)
            result.writestr(entry,data)
    print(output)

if __name__=='__main__': main()
