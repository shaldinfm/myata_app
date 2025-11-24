import os
import re
import base64

def extract_image(svg_path, output_png_path):
    try:
        with open(svg_path, 'r') as f:
            content = f.read()
        
        # Regex to find base64 data
        match = re.search(r'xlink:href="data:image/png;base64,([^"]+)"', content)
        if match:
            base64_data = match.group(1)
            img_data = base64.b64decode(base64_data)
            with open(output_png_path, 'wb') as f:
                f.write(img_data)
            print(f"Extracted {output_png_path}")
        else:
            print(f"No base64 image found in {svg_path}")
    except Exception as e:
        print(f"Error processing {svg_path}: {e}")

base_dir = r"d:/MyataRadio/app/src/main/res/drawable"
extract_image(os.path.join(base_dir, "zaglushka1.svg"), os.path.join(base_dir, "zaglushka_1_img.png"))
extract_image(os.path.join(base_dir, "zaglushka3.svg"), os.path.join(base_dir, "zaglushka_3_img.png"))
extract_image(os.path.join(base_dir, "zaglushka4.svg"), os.path.join(base_dir, "zaglushka_4_img.png"))
