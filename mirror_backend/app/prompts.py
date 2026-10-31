"""Prompts for the three Mirror image operations."""

BASE_MODEL = """\
Create a photorealistic, full-body studio photograph of the exact person shown in the reference photographs.
All references show the same person. Use the first photograph as the primary reference for body and pose.

Preserve, without changes:
- Recognizable facial appearance and features
- Visible body proportions and build
- Skin tone
- Hairstyle and hair color

Depict the person:
- Standing in a neutral, front-facing pose with arms relaxed at their sides
- Showing the full body from head to toe, with nothing cropped
- Against a consistent, plain studio-gray background with even lighting
- Wearing simple, plain, neutral clothing: a fitted gray t-shirt, gray trousers, and plain shoes

Do not beautify, slim, reshape, age, or stylize the person. Do not add accessories or text.
"""

GARMENT_EXTRACTION = """\
Isolate the single primary clothing item in this photograph and remove everything else.
Output the garment alone on a fully transparent background.

Preserve exactly:
- The garment's original silhouette and shape
- Its color, pattern, material, and texture
- Visible construction such as seams, buttons, zippers, collars, sleeves, and pockets

Keep the complete garment visible and centered. Do not crop it.
Do not invent, redesign, repair, or restyle any detail. Do not add a person, mannequin, hanger, shadow, or text.
"""

TRY_ON = """\
The first image is a person. The second image is one clothing item.
Edit the first image so the person is wearing that clothing item.

Preserve, without changes:
- The person's identity and facial features
- Pose and body proportions
- Hairstyle, skin tone, and lighting
- The studio background and framing, with the full body visible

Replace only the clothing that the new item would naturally replace. Keep all other clothing unchanged.
Follow the clothing reference as faithfully as possible, including its color, pattern, material, and
distinctive details. Do not add extra garments, accessories, or text.
"""
