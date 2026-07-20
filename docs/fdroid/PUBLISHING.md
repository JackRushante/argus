# Publishing Argus on F-Droid

This is the maintainer runbook for getting Argus into the F-Droid catalogue and
shipping updates. It is distilled from a prior app's F-Droid history (dozens of
failed pipelines) so the same mistakes are not repeated. **Read it fully before
opening or updating the merge request.**

F-Droid distributes Argus in **Binaries (reproducible) mode**: F-Droid rebuilds
the app from the tagged commit, compares it byte-for-byte (minus signatures)
against the APK we publish on the GitHub release, and — if they match — ships
*our* signed APK. This is why the release build must be deterministic and the
signing key must be pinned.

## 0. Concrete values for the current release

| Field | Value |
|---|---|
| applicationId | `dev.argus` |
| metadata file | `metadata/dev.argus.yml` |
| version | `0.2.3` / base versionCode `5` (ABI split → 501/502/503/504) |
| tag (v-prefixed) | `v0.2.3` |
| build commit (full hash) | `c34358236fb5650f90ecb961ed1bd9d2f2554b84` |
| signing cert SHA-256 | `4c09633e64cf9876b0da682f5f259383af8d22742aadd93ef273b9f2c73cca6b` |
| release asset names | `argus-501.apk` … `argus-504.apk` (pattern `argus-%c.apk`) |
| License (SPDX) | `GPL-3.0-only` |

### ABI split (F-Droid maintainer request, MR !43234)

The universal APK bundled `libandroidx.graphics.path.so` for four ABIs. Per the
F-Droid guide (Quick Start → *Setup ABI split*), the release now emits one APK per
ABI. Mechanics:

- `app/build.gradle.kts`: `splits.abi { isUniversalApk = false; include(...) }`, and
  a per-output `versionCode = 100 * base + {armeabi-v7a:1, arm64-v8a:2, x86:3, x86_64:4}`
  → 501/502/503/504. A `-PargusAbi=<abi>` property restricts the build to a single
  split so F-Droid can build each ABI as its own build block byte-for-byte.
- Recipe: **one `Builds:` block per ABI** (versionCode 501-504, each with
  `gradleprops: [argusAbi=<abi>]`), plus top-level `VercodeOperation: [100*%c+n]` so
  autoupdate generates the four codes for future tags. `CurrentVersionCode: 504`.
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

**Build the published APK from a full `git clone` checked out at the tag — NEVER a
git worktree.** In a worktree `.git` is a pointer file; AGP fails to read it,
injects `NO_VALID_GIT_FOUND`, and reproducibility mismatches. The main tree
`C:\argus` is a normal clone and is fine.

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

- Tag must be **v-prefixed** (`v0.2.1`) so the `Binaries` URL `.../v%v/...` resolves.
- The release asset name must match the `Binaries` pattern exactly: `argus-v0.2.1.apk`.
- The release must target the **exact build commit**: `gh release create v0.2.1
  argus-v0.2.1.apk --target b84dd0c6...`.
- Publish only the **latest** APK per release (no historical bundle).

## 3. The recipe (`metadata/dev.argus.yml`)

A ready draft lives next to this file (`docs/fdroid/dev.argus.yml`). Rules that
cost the prior app its pipelines:

- **`commit:` must be the FULL hash, never a tag** (linsui's rule — confirmed on
  two merged MRs). `commit: v0.2.1` is rejected.
- **Field order** must match `fdroid rewritemeta` output exactly (blank lines and
  a trailing space after `Binaries:` matter). Do NOT hand-format — run
  `fdroid rewritemeta dev.argus` in the fork checkout and commit its output.
- `AllowedAPKSigningKeys` is the lowercase SHA-256, no colons.
- `AntiFeatures: NonFreeNet` is declared honestly (optional proprietary provider
  APIs); the app is otherwise fully functional with the self-hosted bridge and the
  on-device base tier, and has **no** proprietary code dependencies (re2j and
  Dagger/Hilt are Apache-2.0).
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
| reproducible | rebuild matches our APK (minus sig) | VCS info / worktree build / shrink on |

## 5. Per-version update procedure

1. Bump `versionCode`/`versionName`; commit on `master`.
2. Build the reproducible release APK from a clean clone at the new commit; verify
   (section 1).
3. Tag `vX.Y.Z`, push; `gh release create vX.Y.Z argus-vX.Y.Z.apk --target <hash>`.
4. Add a `Builds:` entry (new versionName/versionCode/**full commit hash**); bump
   `CurrentVersion`/`CurrentVersionCode`. Add `fastlane/.../changelogs/<code>.txt`
   in both locales (**≤ 500 chars each** — the client truncates longer).
5. `fdroid rewritemeta` + `fdroid lint`; rebase on upstream; push; watch the pipeline.

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
