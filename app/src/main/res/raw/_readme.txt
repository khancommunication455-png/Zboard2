StyleKit Sound Pack Assets
==========================

This directory holds the built-in key-click sound packs used by
`dev.patrickgold.florisboard.stylekit.appearance.KeySoundManager`.

The KeySoundManager looks up resources by name (see `PACK_TO_RES_NAME`):

  - mechanical  ->  sk_key_mech.ogg
  - soft_pop    ->  sk_key_soft.ogg
  - marimba     ->  sk_key_marimba.ogg

TODO before shipping a release build:
  Drop three short (~50–150ms), mono, 44.1kHz OGG files into this directory
  with the names above. Until then, the manager logs an error and clicks
  are silent (graceful degradation, no crash).

Suggested sources:
  - Mechanical: a short recording of a Cherry MX blue switch.
  - Soft Pop:   a short sine-windowed click.
  - Marimba:    a single marimba note, ~120ms.

All clips MUST be either:
  - Public domain / CC0, OR
  - Properly licensed with attribution added to `res/values/strings.xml`
    `about__third_party_licenses` section.

Files must be ≤ 100KB each to avoid bloating the APK.
