# Bantu-first language package: implementation plan

This extends IMPLEMENTATION_BATCHES.md. The product direction is Bantu-first across the full Bantu language family, not South-Africa-only. Luganda (the user's language) is the first priority, while the rest of the African Bantu catalogue remains visible in regional groups. English stays available as the fallback. This plan does not authorize deleting existing locale resources or claiming unfinished translations are complete. The distinctive Vinz next-song card stays; preserve factual code provenance and required credits for actual reuse.

## Proposed package, subject to language/native review

Keep default English and phone-language following with English fallback. The existing Bantu registry has one canonical entry per language tag and one picker placement per language. Priority order starts with Luganda (lg), followed by isiXhosa (xh), isiZulu (zu), Setswana (tn) and Sesotho (st); regional groups then cover East, Central and Southern Bantu languages. Do not create duplicate entries to represent spelling variants, alternate names or country variants.

Current registry: Luganda (lg), isiXhosa (xh), isiZulu (zu), Setswana (tn), Sesotho (st), Kiswahili (sw), Kinyarwanda (rw), Kirundi (rn), Lingala (ln), Kikongo (kg), chiShona (sn), Chichewa/Chinyanja (ny), Bemba (bem), Tonga/Zambia-Zimbabwe (toi), Silozi (loz), Oshindonga (ng), Oshikwanyama (kj), Otjiherero (hz), isiNdebele/South Ndebele (nr), siSwati (ss), Sepedi/Northern Sotho (nso), Xitsonga (ts), and Tshivenḓa (ve). This is a 23-language starting catalogue, not a claim to cover every Bantu language or variety. Keep language tags unique (case-insensitively), endonyms unique, and each language in exactly one picker section. Preserve meaningful distinctions such as South Ndebele nr versus North Ndebele nd, and Tonga toi versus Tongan to; do not collapse Kikongo or Oshiwambo varieties without native review. Expand only after the first package is complete and reviewed.

## Audit findings

Default English strings are app/src/main/res/values/strings.xml. Locale selection lives in AppLanguage.kt and ui/screens/LanguageSettings.kt. Android13+ uses LocaleManager.applicationLocales; older versions use language/tag shared preferences. The manifest points to @xml/locales_config, apparently generated: verify Gradle generation before editing. I18nGuardTest.kt, TranslationsTest.kt and TranslationTablesTest.kt enforce coverage and correctness. Do not confuse values-v31 or other device/config folders with translation folders.

Lyrics presentation, fetched original lyrics, translation targets and romanization are separate. data/lyrics/Lyrics.kt fetches provider content. LyricsTranslator.kt uses live undocumented translation endpoints with arbitrary target tags; adding a locale code does not prove provider support. Romanizer.kt handles script conversion, not translation. Latin-script Bantu languages normally do not need a new romanization engine.

## Reviewable implementation batches

1. Locale contract/migration, roughly ten meaningful source/test changes. Define English+Bantu registry and endonyms; validate locale-generation behavior and fallback. Normalize retired explicit selections to English on Android13+ and older systems without loops/repeated restarts. Build registry, migration and resource tests before retiring anything. Do not advertise languages with missing resources.
2. UI translation packs, split into small reviewable language groups. Begin with the southern group, then regional expansion. Cover all strings, plurals, interpolation, accessibility labels, notifications and language names. Keep exact English key parity; validate escaping/placeholders. Do not pass English-filled placeholders off as full translations. Machine drafts require native review; only reviewed complete locales enter the shipping picker. Resource sweeps are extra to the approximate ten code/test-file batch target.
3. Safe retirement. Once replacements and migration are ready, remove only the old non-English locale resources and their picker/config entries together. Preserve English, shared assets, API/device configuration resources and all code/dependency license notices. Replace the old all-31-locales rule with exact coverage for the NEW supported set; never disable translation checks. User requested retirement, but do not perform deletion during this planning phase.
4. Lyrics language controls/capabilities, separate approximately ten-file batch. Offer English and verified Bantu translation targets; validate each target against actual service support and test failure, offline/cache and opt-in behavior. Identify unsupported targets honestly rather than fabricating output. Provider content coverage is song-specific: test representative real tracks and available local lyric imports. Preserve original lyrics in whatever language a song supplies; narrowing app/target menus must not erase existing songs or lyrics. Review source, translation and display language labels so they are not conflated.

## Batch gates

- Automated tests, lint, debug build and exact-source APK.
- Per-language native review and representative player/settings/library/search/lyrics/notification/accessibility checks.
- Upgrade from removed locales on Android13+ and older devices; English fallback on unsupported device locales; force-stop/relaunch and restore/import checks.
- Plural/placeholder/long-text/diacritic/font coverage; existing timing, translation opt-in and queue behavior unchanged.
- Deliver a short test checklist, wait for user review, fix feedback, then proceed. No claim of full translation quality or lyric availability without verification.

## Ownership and licensing wording

Treat the user card as a Vinz-specific design direction, not Nexa branding or a pixel-copy request. Do not speculate about whether another project author wrote code. Keep attribution and license obligations factual, tied to actual source/assets/dependencies. Removing UI translations must never remove copyright or license notices.
