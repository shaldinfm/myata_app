import os
import xml.etree.ElementTree as ET
import re

def svg_to_vector_drawable(svg_path, output_path):
    try:
        tree = ET.parse(svg_path)
        root = tree.getroot()
        
        # Namespace map
        ns = {'svg': 'http://www.w3.org/2000/svg'}
        
        # Get dimensions
        width = root.get('width', '24').replace('px', '')
        height = root.get('height', '24').replace('px', '')
        viewport_width = root.get('viewBox', f'0 0 {width} {height}').split()[2]
        viewport_height = root.get('viewBox', f'0 0 {width} {height}').split()[3]

        # Create VectorDrawable root
        vector = ET.Element('vector')
        vector.set('xmlns:android', 'http://schemas.android.com/apk/res/android')
        vector.set('android:width', f'{width}dp')
        vector.set('android:height', f'{height}dp')
        vector.set('android:viewportWidth', viewport_width)
        vector.set('android:viewportHeight', viewport_height)

        # Helper to process paths
        def process_element(element):
            if element.tag.endswith('path'):
                path_data = element.get('d')
                if path_data:
                    path = ET.SubElement(vector, 'path')
                    path.set('android:pathData', path_data)
                    
                    fill = element.get('fill')
                    if fill and fill != 'none':
                        path.set('android:fillColor', fill)
                    else:
                        # Default to black if not specified, or check style
                        style = element.get('style', '')
                        if 'fill' in style:
                            # Extract fill from style
                            pass # Simplified for now
                        else:
                             path.set('android:fillColor', '#FF000000') # Default fill

                    stroke = element.get('stroke')
                    if stroke and stroke != 'none':
                        path.set('android:strokeColor', stroke)
                        path.set('android:strokeWidth', element.get('stroke-width', '1'))
            
            for child in element:
                process_element(child)

        process_element(root)

        # Write to file
        tree = ET.ElementTree(vector)
        ET.indent(tree, space="    ", level=0)
        tree.write(output_path, encoding='utf-8', xml_declaration=True)
        print(f"Converted {svg_path} to {output_path}")

    except Exception as e:
        print(f"Error converting {svg_path}: {e}")

# List of files to convert
files = [
    'home_new',
    'player_new',
    'info_new',
    'donate_new'
]

base_dir = r'd:\MyataRadio\app\src\main\res\drawable'

for filename in files:
    svg_file = os.path.join(base_dir, f'{filename}.svg')
    xml_file = os.path.join(base_dir, f'{filename}.xml')
    
    if os.path.exists(svg_file):
        svg_to_vector_drawable(svg_file, xml_file)
    else:
        print(f"File not found: {svg_file}")
