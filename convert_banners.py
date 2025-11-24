import xml.etree.ElementTree as ET
import re
import os

def convert_svg_to_xml(svg_path, output_path, content_x, content_y, content_w, content_h):
    tree = ET.parse(svg_path)
    root = tree.getroot()
    
    ns = {'svg': 'http://www.w3.org/2000/svg'}
    
    # Create XML root
    vector = ET.Element('vector')
    vector.set('xmlns:android', 'http://schemas.android.com/apk/res/android')
    vector.set('android:width', f'{content_w}dp')
    vector.set('android:height', f'{content_h}dp')
    vector.set('android:viewportWidth', str(content_w))
    vector.set('android:viewportHeight', str(content_h))
    
    # Main group for translation
    main_group = ET.SubElement(vector, 'group')
    main_group.set('android:translateX', str(-content_x))
    main_group.set('android:translateY', str(-content_y))
    
    # Find all paths
    # We need to look recursively. 
    # Also handle fills.
    
    def process_element(element):
        if element.tag.endswith('path'):
            d = element.get('d')
            fill = element.get('fill')
            stroke = element.get('stroke')
            stroke_width = element.get('stroke-width')
            
            path_node = ET.SubElement(main_group, 'path')
            path_node.set('android:pathData', d)
            
            if fill and fill != 'none':
                color_map = {'black': '#000000', 'white': '#FFFFFF'}
                path_node.set('android:fillColor', color_map.get(fill, fill))
            
            if stroke and stroke != 'none':
                color_map = {'black': '#000000', 'white': '#FFFFFF'}
                path_node.set('android:strokeColor', color_map.get(stroke, stroke))
                if stroke_width:
                    path_node.set('android:strokeWidth', stroke_width)
                else:
                    path_node.set('android:strokeWidth', "1") # Default if not specified but stroke exists
                    
        for child in element:
            process_element(child)

    process_element(root)
    
    # Write to file
    tree = ET.ElementTree(vector)
    ET.indent(tree, space="    ", level=0)
    tree.write(output_path, encoding='utf-8', xml_declaration=True)
    print(f"Converted {svg_path} to {output_path}")

# Configuration based on analysis of SVGs (mask rect x=7, width=316, height=198)
# All 3 SVGs seem to share the same structure/dimensions for the mask.
# gold_banner_new.svg: mask rect x="7" ... width="316" height="198"
# xtra_banner_new.svg: mask rect x="7" ... width="316" height="198"
# myata_banner_new.svg: mask rect x="7" ... width="316" height="198"

files = [
    ('app/src/main/res/drawable/gold_banner_new.svg', 'app/src/main/res/drawable/gold_banner_new.xml'),
    ('app/src/main/res/drawable/xtra_banner_new.svg', 'app/src/main/res/drawable/xtra_banner_new.xml'),
    ('app/src/main/res/drawable/myata_banner_new.svg', 'app/src/main/res/drawable/myata_banner_new.xml')
]

for inp, out in files:
    convert_svg_to_xml(inp, out, 7, 0, 316, 198)
