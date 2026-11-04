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

Wardrobes and personal images are private user data. Personal images leave the device only through Mirror and through Extract from photo, described below.

### Extract from photo (garment import)

- Decided 2026-11-03: there is no separate consent or submit step. Choosing a photo under Add a piece › Extract from photo uploads it; the privacy disclosure is part of signup (not built yet). The Add a piece sheet and the import page say plainly that the photo is uploaded and that nothing is added to the wardrobe until the user reviews and saves.
- The uploaded bytes are the 1600 px re-encoded display copy, so no location or camera metadata is sent. The photo goes to the Wadrova backend (`wadrova-backend`, `/v1/imports`) for SAM 3 segmentation, never to OpenAI.
- Detected pieces are never added automatically. Segmentation finds regions; it is not reliable recognition of category, colour, fabric, brand or style, and the app must not present it as such.
- **Not built yet:** server-side deletion and retention. The server keeps uploaded photos and results, including after a cancel. Until those controls exist, integration testing uses only non-personal photos (such as the CC0 test photos outside the repository).

### Credentials

- Backend tokens (the Mirror development token and the Wadrova access token) are kept in the iOS Keychain (this device only) or, on Android, in files under `noBackupFilesDir` encrypted with an Android Keystore key. They are never in the database, logs, URLs or error messages. Earlier versions kept the Mirror token in `app_setting`; it is moved on first read.
- Until sign-in exists, the Wadrova access token is an operator-issued development token entered on the import's development connection page. The address must be https.

### Mirror and OpenAI

- Mirror sends images to OpenAI through a backend the user runs. The API key stays on that backend and is never in the app.
- Each send needs explicit consent in a dialog. Selecting a photo or opening a screen sends nothing.
- A generated image is used only after the user approves it. Previews never claim to show fit or size.
- Reference photos are deleted from the device once a model is approved or the flow is cancelled.
- Generated images are stored like garment photos, excluded from cloud backups, and can be removed by the user.

### Photo storage and backups

- Garment photos come from the system photo picker, so the app requests no photo-library permission.
- The app stores only re-encoded copies: a 1600 px display image and a 640 px thumbnail. The original file and its metadata, including location, are not kept.
- Android stores photos in `noBackupFilesDir`, which Auto Backup excludes. iOS stores them in a folder marked `NSURLIsExcludedFromBackupKey`.
- The database's backup behavior is unchanged, so it can still be backed up. It holds garment names, details, saved looks, garment-import records (states, server ids, photo hashes), the backend addresses and opaque photo ids, but no images or tokens.
- After a restore, a garment can refer to a photo that isn't on the device. The app then shows its illustration, and the user can add the photo again.
