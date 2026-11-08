# Sing model package recovery

## Status and approval boundary

The app's four Sing ZIP URLs under `nastechtest/Vinz` / `models-v1` returned HTTP 404 during diagnosis. This repair recovers the original upstream model/native payloads and creates new, reproducible ZIPs. It does not train or convert a model.

These ZIPs do **not** match the old ZIP hashes. The updated ModelStore metadata and a new review APK are required; publishing these files alone will not fix existing APKs. The Japanese dictionary metadata is unchanged and its legacy archive layout remains supported.

This branch and its workflows publish **CI review artifacts only**. No workflow creates or publishes a GitHub release, merges code, or changes a production installation. Keep the repair PR in draft until package publication has been explicitly approved and the phone checks below pass. Do not claim real separation works based on download or JVM checks alone.

## Provenance and licences

`scripts/models/sources.json` records the exact public upstream URLs, sizes and SHA-256 pins for the sherpa-onnx Spleeter fp16 source archive, Microsoft's ONNX Runtime 1.31.0 Android AAR, and full licence notices. The builder reads only the two named ONNX files and the three supported ABI libraries, without extracting arbitrary source paths. It copies their bytes unchanged. `packages.json` records every original payload hash, ZIP layout, compressed/unpacked sizes and output SHA-256.

Model ZIP: Deezer MIT notice and sherpa-onnx Apache-2.0 notice. Runtime ZIPs: Microsoft MIT notice and the full version-pinned ONNX Runtime ThirdPartyNotices.txt. Those are source attribution records, not an independent legal determination of trained-weight redistribution rights. Confirm trained-weight redistribution rights before approving public redistribution. No model binary is committed to this repository.

## Rebuild and verify

Requires Python 3 with stdlib ZIP/DEFLATE support and public HTTPS access. Missing sources are downloaded from the pinned URLs, then their lengths and SHA-256 values are checked before any payload is used. Existing mismatched sources are rejected, not silently reused.

```sh
python3 scripts/tests/test_sing_packages.py
python3 scripts/models/package_sing.py \
  --source-dir /tmp/vinz-sing-sources \
  --output-dir /tmp/vinz-sing-packages \
  --check-source app/src/main/java/com/vinz/music/data/ModelStore.kt
```

ZIP entries have sorted names, fixed 1980 timestamps, regular 0644 modes and DEFLATE level 9. The committed manifest is the final reproducibility gate: differences, including compressor-version differences, fail the build. Do not use `--write-manifest` to make unexpected CI drift pass. That flag is only for deliberate, reviewed package regeneration with corresponding ModelStore updates.

The `Sing model packages` PR workflow independently downloads and verifies the public sources, runs packaging regressions, compares all four outputs against the committed manifest and uploads the ZIPs plus provenance/checksum files as review artifacts.

## Installation guards

Downloads retain SHA-256 verification before installation. All failure paths clean the `.part` file. Sing ZIPs must have safe, flat, unique entry names, bounded expansion and nonempty regular required files. A ready marker must contain the pinned archive SHA and all required files must still exist. The marker is written last; failed installs remove the incomplete directory. This is package integrity validation, not a cryptographic rehash of installed files on every readiness query.

The existing Japanese dictionary's basename flattening remains unchanged for compatibility. The stricter Sing name/duplicate/expansion policy is not applied to its legacy archives.

## Explicitly approved publication, later

1. Review upstream provenance, notices, package contents and all CI checks.
2. Obtain approval before uploading any assets to a public release. Check again whether `models-v1` or any asset already exists. Never overwrite a published asset silently or modify an existing release without approval.
3. If the tag/assets remain absent, an authorised maintainer can stage the four verified ZIPs and manifests in a draft `models-v1` release. A draft is not publicly downloadable and cannot satisfy the app.
4. Publish only after explicit approval. Verify each exact unauthenticated URL, SHA-256, download length and archive layout from outside the authenticated session. If a different immutable release tag is chosen, update ModelStore deliberately and rebuild the review APK first.
5. Run the phone checks below. Keep this PR unmerged and do not make an app release without further approval.

## Phone validation

Back up the installed app's data before any uninstall. Debug-signed review APKs cannot update a release-signed installation. Prefer a side-by-side build (`-PsideBySide`) or a spare device; never uninstall the user's app automatically.

- [ ] Install the exact review APK and note its commit and version.
- [ ] Confirm the runtime selected matches the device ABI.
- [ ] Download both Sing packages over their final public URLs; verify progress, successful checksum/content installation and Ready state after restarting the app.
- [ ] Interrupt a download, retry it, and check that no stale partial install is treated as Ready.
- [ ] Load the native runtime and real ONNX sessions on the device.
- [ ] Play a song with foreground and backing vocals; enable Sing and verify actual vocal reduction, audible accompaniment, stable playback and correct seek alignment.
- [ ] Verify quick-mode fallback and existing lyrics/playback are unchanged.

`SingWaysTest.liveSingDownloadsInstallAndLoadOnAnOptedInDevice` is skipped by default. After publication, explicitly run that test with instrumentation argument `singVerifyLiveDownloads=true` to exercise real download, installation and native runtime loading. It does **not** claim to cover real vocal inference or listening quality. The existing `SingSpeedTest` can exercise real model sessions; the playback/listening checklist above is still required.
