import os
import math
from PIL import Image, ImageDraw, ImageFilter

def create_base_icon(size=1024, is_round=False):
    # High-resolution canvas
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    
    # 1. Background
    # Create gradient background
    bg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    bg_draw = ImageDraw.Draw(bg)
    
    # Linear gradient #0D1117 -> #0A192F
    for y in range(size):
        r = int(13 + (10 - 13) * (y / size))
        g = int(17 + (25 - 17) * (y / size))
        b = int(23 + (47 - 23) * (y / size))
        bg_draw.line([(0, y), (size, y)], fill=(r, g, b, 255))
        
    # Draw concentric radar / wireless rings
    center = (size // 2, size // 2)
    for radius in [int(size * 0.42), int(size * 0.35), int(size * 0.28)]:
        bbox = [center[0] - radius, center[1] - radius, center[0] + radius, center[1] + radius]
        bg_draw.ellipse(bbox, outline=(37, 75, 125, 100), width=max(2, size // 256))
        
    # Mask background (rounded rect or circle)
    mask = Image.new("L", (size, size), 0)
    mask_draw = ImageDraw.Draw(mask)
    if is_round:
        mask_draw.ellipse([int(size * 0.04), int(size * 0.04), int(size * 0.96), int(size * 0.96)], fill=255)
    else:
        corner_r = int(size * 0.22)
        mask_draw.rounded_rectangle([int(size * 0.04), int(size * 0.04), int(size * 0.96), int(size * 0.96)], radius=corner_r, fill=255)
        
    img.paste(bg, (0, 0), mask)
    draw = ImageDraw.Draw(img)
    
    # Border stroke
    stroke_color = (56, 225, 255, 180)
    if is_round:
        draw.ellipse([int(size * 0.04), int(size * 0.04), int(size * 0.96), int(size * 0.96)], outline=stroke_color, width=max(3, size // 160))
    else:
        draw.rounded_rectangle([int(size * 0.04), int(size * 0.04), int(size * 0.96), int(size * 0.96)], radius=int(size * 0.22), outline=stroke_color, width=max(3, size // 160))
        
    # 2. TV Screen Frame
    tv_w = int(size * 0.52)
    tv_h = int(size * 0.34)
    tv_x0 = (size - tv_w) // 2
    tv_y0 = int(size * 0.24)
    tv_x1 = tv_x0 + tv_w
    tv_y1 = tv_y0 + tv_h
    tv_radius = int(size * 0.05)
    
    # Screen inner fill (semi-transparent glassmorphic)
    draw.rounded_rectangle([tv_x0, tv_y0, tv_x1, tv_y1], radius=tv_radius, fill=(15, 25, 45, 220), outline=(56, 225, 255, 255), width=max(4, size // 100))
    
    # Inner display reflection highlight
    draw.rounded_rectangle([tv_x0 + 8, tv_y0 + 8, tv_x1 - 8, tv_y0 + tv_h // 2], radius=tv_radius - 4, fill=(255, 255, 255, 20))
    
    # 3. AirPlay Wireless Triangle Beam
    tri_w = int(size * 0.30)
    tri_h = int(size * 0.22)
    tri_top = (size // 2, int(size * 0.48))
    tri_left = (size // 2 - tri_w // 2, tri_top[1] + tri_h)
    tri_right = (size // 2 + tri_w // 2, tri_top[1] + tri_h)
    
    # Triangle glow
    glow_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow_img)
    glow_draw.polygon([tri_top, tri_right, tri_left], fill=(0, 229, 255, 140))
    glow_img = glow_img.filter(ImageFilter.GaussianBlur(size // 40))
    img.alpha_composite(glow_img)
    
    # Main Triangle
    draw = ImageDraw.Draw(img)
    draw.polygon([tri_top, tri_right, tri_left], fill=(10, 132, 255, 255), outline=(255, 255, 255, 255))
    
    # 4. TV Stand Bar
    stand_w = int(size * 0.22)
    stand_h = max(4, int(size * 0.024))
    stand_y = int(size * 0.74)
    stand_x0 = (size - stand_w) // 2
    stand_x1 = stand_x0 + stand_w
    draw.rounded_rectangle([stand_x0, stand_y, stand_x1, stand_y + stand_h], radius=stand_h // 2, fill=(100, 210, 255, 255))
    
    return img

def create_banner(width=1280, height=720):
    img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    
    # Cinematic dark gradient
    for x in range(width):
        t = x / width
        r = int(11 * (1 - t) + 14 * t)
        g = int(15 * (1 - t) + 25 * t)
        b = int(23 * (1 - t) + 42 * t)
        draw.line([(x, 0), (x, height)], fill=(r, g, b, 255))
        
    # Decorative ambient light
    draw.polygon([(0, 0), (int(width * 0.45), 0), (int(width * 0.25), height), (0, height)], fill=(0, 229, 255, 12))
    
    # Left Emblem
    emblem_size = int(height * 0.70)
    emblem_icon = create_base_icon(emblem_size, is_round=False)
    emblem_x = int(width * 0.08)
    emblem_y = (height - emblem_size) // 2
    img.paste(emblem_icon, (emblem_x, emblem_y), emblem_icon)
    
    # Right-side branding graphics
    draw = ImageDraw.Draw(img)
    
    # Live status dot + Badge
    dot_x = int(width * 0.46)
    dot_y = int(height * 0.26)
    draw.ellipse([dot_x, dot_y, dot_x + 18, dot_y + 18], fill=(48, 209, 88, 255))
    
    # Pill badge "4K ULTRA LOW LATENCY"
    badge_x0 = dot_x + 30
    badge_y0 = dot_y - 4
    badge_x1 = badge_x0 + int(width * 0.38)
    badge_y1 = badge_y0 + 26
    draw.rounded_rectangle([badge_x0, badge_y0, badge_x1, badge_y1], radius=13, fill=(31, 46, 69, 240), outline=(56, 225, 255, 180), width=2)
    
    # Title bar (Stylized AIRPLAY TV)
    title_y0 = int(height * 0.38)
    draw.rounded_rectangle([dot_x, title_y0, dot_x + int(width * 0.45), title_y0 + 44], radius=22, fill=(255, 255, 255, 250))
    
    # Subtitle bars
    sub_y0 = int(height * 0.52)
    draw.rounded_rectangle([dot_x, sub_y0, dot_x + int(width * 0.35), sub_y0 + 20], radius=10, fill=(125, 139, 159, 230))
    
    sub_y1 = int(height * 0.60)
    draw.rounded_rectangle([dot_x, sub_y1, dot_x + int(width * 0.25), sub_y1 + 14], radius=7, fill=(78, 93, 115, 200))
    
    # Bottom cyan accent line
    draw.line([(0, height - 6), (width, height - 6)], fill=(10, 132, 255, 255), width=6)
    
    return img

def main():
    base_res = "app/src/main/res"
    
    # Density map for launcher icons: mdpi (48), hdpi (72), xhdpi (96), xxhdpi (144), xxxhdpi (192)
    densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    
    # Generate 1024x1024 master icons
    master_square = create_base_icon(1024, is_round=False)
    master_round = create_base_icon(1024, is_round=True)
    
    # Save standard mipmap PNGs
    for folder, dim in densities.items():
        dir_path = os.path.join(base_res, folder)
        os.makedirs(dir_path, exist_ok=True)
        
        sq = master_square.resize((dim, dim), Image.Resampling.LANCZOS)
        sq.save(os.path.join(dir_path, "ic_launcher.png"), "PNG")
        
        rd = master_round.resize((dim, dim), Image.Resampling.LANCZOS)
        rd.save(os.path.join(dir_path, "ic_launcher_round.png"), "PNG")
        print(f"Generated {folder}/ic_launcher.png ({dim}x{dim}) and ic_launcher_round.png")
        
    # TV Banners: mdpi (320x180), hdpi (480x270), xhdpi (640x360), xxhdpi (960x540), xxxhdpi (1280x720)
    banner_densities = {
        "drawable-mdpi": (320, 180),
        "drawable-hdpi": (480, 270),
        "drawable-xhdpi": (640, 360),
        "drawable-xxhdpi": (960, 540),
        "drawable-xxxhdpi": (1280, 720),
    }
    
    master_banner = create_banner(1280, 720)
    for folder, (bw, bh) in banner_densities.items():
        dir_path = os.path.join(base_res, folder)
        os.makedirs(dir_path, exist_ok=True)
        b_img = master_banner.resize((bw, bh), Image.Resampling.LANCZOS)
        b_img.save(os.path.join(dir_path, "banner_tv.png"), "PNG")
        b_img.save(os.path.join(dir_path, "tv_banner.png"), "PNG")
        print(f"Generated {folder}/tv_banner.png ({bw}x{bh})")

    # Also save Google Play Store 512x512 Hi-Res Icon
    os.makedirs("dist", exist_ok=True)
    play_icon = master_square.resize((512, 512), Image.Resampling.LANCZOS)
    play_icon.save("dist/ic_launcher-playstore-512.png", "PNG")
    master_banner.save("dist/tv_banner-store-1280x720.png", "PNG")
    print("Generated dist/ic_launcher-playstore-512.png and dist/tv_banner-store-1280x720.png")

if __name__ == "__main__":
    main()
