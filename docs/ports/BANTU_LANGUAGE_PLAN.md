# English + Bantu language package: planning proposal

This extends IMPLEMENTATION_BATCHES.md. Planning only: no locale resources are deleted and no app or lyrics behavior changes in this commit. The user requests English as the retained existing UI language, replacing other bundled languages with broad Bantu coverage, in app UI and lyrics language controls. The distinctive Vinz next-song card stays; do not describe visual similarity as proof of copied authorship. Preserve factual code provenance and required credits for actual reuse.

## Proposed package, subject to language/native review

Keep default English and phone-language following with English fallback. Initial southern group: isiZulu (zu), isiXhosa (xh), isiNdebele/South Ndebele (nr), siSwati (ss), Sepedi/Northern Sotho (nso), Sesotho (st), Setswana (tn), Xitsonga (ts), Tshivenda (ve).

Broader proposal: Kiswahili (sw), chiShona (sn), Chichewa/Chinyanja (ny), Kinyarwanda (rw), Kirundi (rn), Luganda (lg), Lingala (ln), Kikongo (kg), Bemba (bem), Tonga/Zambia-Zimbabwe (toi), Silozi (loz), Oshindonga (ng), Oshikwanyama (kj), Otjiherero (hz). Together these form a proposed 23-language Bantu starting package, not a claim to cover all Bantu languages or varieties. Validate endonyms, regional variants, Android resource qualifiers and BCP47/provider mappings before implementation. Distinguish South Ndebele nr from North Ndebele nd; Tonga toi from Tongan to; do not collapse Kikongo varieties or Oshiwambo varieties without review. Expand after the first package is complete and reviewed.

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
