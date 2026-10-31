# Wadrova Product Brief

Wadrova helps people understand, organize, style, and make better use of the clothing they own. It should understand not just what someone wears, but how they wear it and how their style evolves.

The intended experience is modern, youthful, visually polished, intuitive, and deeply personalized.

## Capabilities

These describe the product's direction, not what is built today.

- **Digital Wardrobe:** catalog real clothing and accessories through photographs, with attributes and organization.
- **Outfit Builder:** manually create and save outfits from the wardrobe.
- **AI Stylist:** recommend outfits from owned garments, using occasion, context, preferences, and personal style.
- **Adaptive Personalization:** learn from accepted and rejected recommendations, manually created outfits, and photos of outfits actually worn.
- **Styling Intelligence:** recommend how clothing is worn, such as sleeve rolls, partial tucks, layering, and scarf arrangements ("Style Moves").
- **Wadrova Mirror:** preview outfits on a personalized visual representation of the user.
- **Collections:** organize saved outfits into lookbooks.
- **Discover:** recommend external products that complement the existing wardrobe.

## Outfit behavior decisions

These are agreed rules for upcoming features. Some differ from the current code and aren't implemented yet.

- Archived garments remain valid in existing looks but are excluded from new garment selection by default.
- Default variation names receive sequential suffixes without repeated suffixes.
- Unknown garment references must eventually be surfaced to the user rather than silently hidden.
- Duplicate garment IDs are invalid in a saved outfit.
- Swapping preserves the garment's position. Selecting the current item in swap mode does nothing; removal is a distinct action.
- Styling around an accessory must not implicitly remove other accessories.
- Outfit completeness rules must be consistent across Builder, Mirror, and Stylist. Dresses are alternatives to top-and-bottom combinations.

## Honesty rules

- Never fabricate products, prices, availability, or retailer links.
- Never present a demo model or a static image as a personalized try-on.
- Clearly label sample and illustrative content.
- Describe rule-based suggestions as rules, not as AI or learning.

## Privacy

Wardrobes and personal images are private user data. Personal images must not leave the device until storage, access control, and deletion have an agreed design.
