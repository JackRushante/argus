# Publishing Argus on F-Droid

This is the maintainer runbook for getting Argus into the F-Droid catalogue and
shipping updates. It is distilled from a prior app's F-Droid history (dozens of
failed pipelines) so the same mistakes are not repeated. **Read it fully before
opening or updating the merge request.**

**CURRENT MODE (since 2026-07-21): reproducible, developer-signed `Binaries`.**
F-Droid rebuilds each per-ABI APK from the pinned commit, strips the signature for
comparison, and publishes the verified developer-signed APK. The recipe therefore
**must keep** both `Binaries:` and `AllowedAPKSigningKeys:`.

The initial mismatch was not an unavoidable Windows/Linux difference. The APKs
originally uploaded for v0.2.4 came from a dirty incremental build. After dropping
the non-byte-stable ART baseline profile (`d097aed`) and rebuilding from a clean
tree with the build cache disabled, `classes2.dex` became deterministic across all
four ABIs and across Windows/F-Droid Linux. F-Droid's own `check apk` job verified
the match on 2026-07-21. Every release must therefore use clean, project-signed
per-ABI builds; uploading an incremental APK will break the pipeline.

## 0. Concrete values for the current published release

The table and checked-in recipe intentionally stay on the last **tagged release with uploaded,
reproducible assets**. A development branch may already carry the next `versionName` and Fastlane
changelog; do not point F-Droid at it until the GitHub PR is merged, the final full commit is tagged,
and all four signed `argus-%c.apk` assets exist. This avoids a recipe whose `commit:` or `Binaries:`
URL cannot be built yet.

| Field | Value |
|---|---|
| applicationId | `dev.argus` |
| metadata file | `metadata/dev.argus.yml` |
| version | `0.3.0` / base versionCode `7` (ABI split → 701/702/703/704) |
| tag (v-prefixed) | `v0.3.0` |
| build commit (full hash) | `5a73ea62f9837f5371c1216fd4f9e57b5bc94c72` |
| signing cert SHA-256 | `4c09633e64cf9876b0da682f5f259383af8d22742aadd93ef273b9f2c73cca6b` |
| F-Droid release assets | `argus-701.apk` … `argus-704.apk` (pattern `argus-%c.apk`) |
| direct-download asset | `argus-v0.3.0-universal.apk` (ignored by F-Droid) |
| License (SPDX) | `GPL-3.0-only` |

### Reproducibility: baseline profile dropped (v0.2.4)

v0.2.3's `fdroid build` job failed the byte comparison: F-Droid's clean rebuild produced a
different `assets/dexopt/baseline.prof` and profile-ordered `classes2.dex` (only those two files;
`classes.dex`/`classes3.dex` matched). The ART baseline profile compiled from the merged AndroidX
library profiles is not byte-stable across build hosts. Fix in `app/build.gradle.kts`: disable the
release ART/startup profile tasks so no baseline profile is packaged and dex layout is
source-ordered —

```kotlin
tasks.configureEach {
    if (name == "compileReleaseArtProfile" ||
        name == "mergeReleaseArtProfile" ||
        name == "mergeReleaseStartupProfile") enabled = false
}
```

Keep this for every future release. Only cost is losing profile-guided startup optimisation.

### ABI split (F-Droid maintainer request, MR !43234)

The universal APK bundled `libandroidx.graphics.path.so` for four ABIs. Per the
F-Droid guide (Quick Start → *Setup ABI split*), the release now emits one APK per
ABI. Mechanics:

- `app/build.gradle.kts`: `splits.abi { isUniversalApk = false; include(...) }`, and
  a per-output `versionCode = 100 * base + {armeabi-v7a:1, arm64-v8a:2, x86:3, x86_64:4}`
  → for base code 7: 701/702/703/704. A `-PargusAbi=<abi>` property restricts the build to a single
  split so F-Droid can build each ABI as its own build block byte-for-byte.
- Recipe: **one `Builds:` block per ABI** (for v0.3.0: versionCode 701-704, each with
  `gradleprops: [argusAbi=<abi>]`), plus top-level `VercodeOperation: [100*%c+n]` so
  autoupdate generates the four codes for future tags. `CurrentVersionCode` is the
  highest split code.
- Release assets are the four **single-ABI** APKs, built with `-PargusAbi=<abi>` so
  they match F-Droid's per-block rebuild exactly. Naming `argus-<versionCode>.apk`
  matches `Binaries: .../v%v/argus-%c.apk`.
- Version-code order must stay `armeabi-v7a < arm64-v8a < x86 < x86_64` with the ABI
  digit in the lowest position, else clients pick the wrong split.

## 1. Reproducible release build (already in `app/build.gradle.kts`)

Three settings on the `release` buildType / `android {}` are what make F-Droid's
rebuild match ours. **Do not remove them.**

- `dependenciesInfo { includeInApk = false; includeInBundle = false }` — drops the
  AGP "Dependency metadata" block (`0x504b4453`) from the APK signing block, which
  F-Droid's `check apk` job rejects.
- `vcsInfo { include = false }` — no git SHA in
  `META-INF/version-control-info.textproto`. F-Droid builds from a clean clone; a
  baked-in SHA would differ and break reproducibility.
- `isShrinkResources = false` (and `isMinifyEnabled = false`) — resource shrinking
  renamed resources non-deterministically.

Build from a clean clone or a clean, detached worktree checked out at the exact tag.
`vcsInfo.include = false`, so the `.git` representation is not embedded in the APK.
Never build release assets from a dirty/incremental tree. Use `clean`,
`--no-build-cache`, stop competing Gradle daemons, and build one ABI at a time.

Verify the produced APK before publishing:

```bash
# no VCS info
unzip -l app-release.apk | grep version-control-info   # must print nothing
# signing block carries only v2 signature + verity padding, no 0x504b4453
python docs/fdroid/verify_apk_block.py app-release.apk  # see script below
# signing cert fingerprint (must equal AllowedAPKSigningKeys)
apksigner verify --print-certs app-release.apk | grep -i SHA-256
```

## 2. GitHub release

- Tag must be **v-prefixed** (`v0.3.0`) so the `Binaries` URL `.../v%v/...` resolves.
- The four F-Droid assets must match `argus-%c.apk` exactly (`argus-701.apk` through
  `argus-704.apk` for v0.3.0).
- The release must target the exact commit pinned by every recipe block.
- An extra universal APK may be published for direct downloads. Its name must not
  match `argus-%c.apk`; F-Droid intentionally ignores it.

## 3. The recipe (`metadata/dev.argus.yml`)

A ready draft lives next to this file (`docs/fdroid/dev.argus.yml`). Rules that
cost the prior app its pipelines:

- **`commit:` must be the FULL hash, never a tag** (linsui's rule — confirmed on
  two merged MRs). `commit: v0.2.1` is rejected.
- **Field order** must match `fdroid rewritemeta` output exactly (blank lines and
  a trailing space after `Binaries:` matter). Do NOT hand-format — run
  `fdroid rewritemeta dev.argus` in the fork checkout and commit its output.
- `AllowedAPKSigningKeys` is the lowercase SHA-256, no colons.
- Do **not** add `AntiFeatures: NonFreeNet` for the optional proprietary LLM
  providers. Maintainer linsui explicitly requested its removal: the integrations
  are optional and the app can use a self-hosted bridge.
- `gradle: [yes]` — Argus has no product flavors, so `yes` is the correct token.
- Keep `CurrentVersion`/`CurrentVersionCode` in sync with the highest git tag, or
  the `checkupdates` job fails.

Fastlane metadata (title, descriptions, changelog, screenshots) lives in this repo
under `fastlane/metadata/android/{en-US,it}/` and is imported automatically by
F-Droid — it does not go in the recipe.

## 4. Opening / updating the merge request (needs the GitLab account)

1. Clone the fork **non-shallow** (a shallow push is rejected):
   `git clone https://gitlab.com/JackRushante/fdroiddata` and add the upstream
   remote `https://gitlab.com/fdroid/fdroiddata`.
2. **Rebase the branch on upstream `master`** before every push — a stale fork
   causes "added in both" conflicts on the metadata file.
3. Add `metadata/dev.argus.yml` + copy the fastlane tree is not needed (F-Droid
   reads fastlane from the app repo). Run `fdroid rewritemeta dev.argus` and
   `fdroid lint dev.argus`; fix everything they report.
4. Branch name: `dev.argus`. Push with the MR push-option to open the MR against
   `fdroid/fdroiddata`.
5. **Clean up failed pipelines** — keep only the green run; leftover red pipelines
   from force-pushes confuse the review.

### Pipeline jobs (what each one checks)

| Job | Checks | Common failure |
|---|---|---|
| `fdroid rewritemeta` | YAML byte-identical to canonical output | field order, blank lines, trailing space |
| `fdroid build` | clone → checkout `commit` → gradle build | commit hash unreachable after a force-push |
| `checkupdates` | highest tag vs `CurrentVersion` | a tag higher than the metadata version |
| `check apk` | scans the published Binaries APK | dependency-metadata block, signing mismatch |
| reproducible | rebuild matches our APK (minus sig) | dirty cache / baseline profile / shrink on |

## 5. Per-version update procedure

1. Bump `versionCode`/`versionName`; commit on `master`.
2. Commit and tag the exact release commit (`vX.Y.Z`), then create a clean detached
   clone/worktree at that tag.
3. For each ABI, run `clean` followed by `:app:assembleRelease
   -PargusAbi=<abi> --no-build-cache`; rename the signed output to
   `argus-<splitVersionCode>.apk`. Verify the cert fingerprint and compare the dex
   CRCs across clean rebuilds.
4. Create the GitHub release at the tag and upload all four split APKs. Optionally
   add a separately built universal APK for direct downloads.
5. Add four `Builds:` entries with the new versionName, split versionCodes and the
   same **full commit hash**; bump `CurrentVersion`/`CurrentVersionCode`. Add
   `fastlane/.../changelogs/<splitCode>.txt` in both locales (**≤ 500 chars each**).
6. Run `fdroid rewritemeta` + `fdroid lint`; rebase on upstream; push; require both
   `fdroid build` and `check apk` to pass before calling the release reproducible.

## 5b. Gotchas found on the first MR (!43234)

The first pipeline failed two jobs — both fixed in v0.2.2. Watch for these:

- **`schema validation`**: `AutoUpdateMode: Version v%v` is rejected by the current
  schema. Use just `AutoUpdateMode: Version` (F-Droid derives the tag from the
  Binaries `v%v` pattern). Keep `UpdateCheckMode: Tags`.
- **`fdroid build` scanner**: `org.gradle.toolchains.foojay-resolver-convention` is a
  "usual suspect" (it provisions JDKs from a remote service at build time) and blocks
  the build. It was removed from `settings.gradle.kts`; `engine-core` sets the JVM 17
  target explicitly instead of `jvmToolchain(17)`. **Never reintroduce foojay or a
  `jvmToolchain{}` that triggers a remote download** — F-Droid builds with a local
  JDK 17.

## 6. Privacy / distribution invariants

- The public repo must never carry the real bridge host/token or any homelab
  identifier (the Tailscale hostname was scrubbed; internal planning docs are
  untracked). Re-scan before each release.
- API keys are entered by the user and encrypted on device; nothing about them
  ships in the APK or the repo.
