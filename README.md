<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="140" height="140" />
</p>

<h1 align="center">Kodex — Code · Edit · Build</h1>

<p align="center">
  <b>A native Android code editor with real Git — fast editing, source control and previews, with no bundled toolchain.</b>
  <br>
  <i>No SDK manager, no terminal, no Ubuntu rootfs — just your project folder, a fast editor and Git.</i>
</p>

> [!NOTE]
> **Kodex 1.1.0 is out.** Grab the APK for your phone from the
> [latest release](https://github.com/Utsavrajputt/Kodex/releases/latest) — see
> [APK variants](#-apk-variants) if you're not sure which one — and read what's new in
> [CHANGELOG.md](CHANGELOG.md).

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%2011%2B-brightgreen.svg" />
  <img src="https://img.shields.io/badge/Kotlin-Compose%20%2F%20Material%203-7F52FF.svg?logo=kotlin&logoColor=white" />
  <img src="https://img.shields.io/github/v/release/Utsavrajputt/kodex.svg?logo=github&label=Release&include_prereleases&cacheSeconds=3600" />
  <img src="https://img.shields.io/github/downloads/Utsavrajputt/kodex/total?logo=github&cacheSeconds=3600" />
</p>

<p align="center">
  <a href="https://github.com/Utsavrajputt/kodex/actions/workflows/android-build.yml">
    <img src="https://img.shields.io/github/actions/workflow/status/Utsavrajputt/kodex/android-build.yml?branch=main&logo=github&label=Build" />
  </a>
  <a href="https://github.com/Utsavrajputt/kodex/actions/workflows/release.yml">
    <img src="https://img.shields.io/github/actions/workflow/status/Utsavrajputt/kodex/release.yml?logo=github&label=Release%20Build" />
  </a>
  <a href="https://github.com/Utsavrajputt/kodex/actions/workflows/prerelease.yml">
    <img src="https://img.shields.io/github/actions/workflow/status/Utsavrajputt/kodex/prerelease.yml?logo=github&label=Pre-release%20Build" />
  </a>
</p>

<p align="center">
  <img src="https://img.shields.io/github/stars/Utsavrajputt/kodex?style=flat&logo=github&color=gold" />
  <img src="https://img.shields.io/github/forks/Utsavrajputt/kodex?style=flat&logo=github&color=blue" />
  <img src="https://img.shields.io/github/last-commit/Utsavrajputt/kodex?style=flat&logo=github" />
  <img src="https://img.shields.io/github/issues/Utsavrajputt/kodex?style=flat&logo=github&color=orange" />
</p>

<p align="center">
  <sub>⭐ • 🍴 • 🕓 • 🐛 &nbsp;—&nbsp; if Kodex's useful to you, a star helps more than you'd think</sub>
</p>

---

## 💡 Why Kodex?

Closed-source editors cover this niche already. Kodex is an open, from-scratch take on the part that
matters on a phone: **editing code and managing it with Git**. Building and running is deliberately
left to GitHub Actions or a PC — that is a different (and much heavier) problem.

---

## ✨ Features

<details>
<summary><b>📂 Projects & files</b></summary>

| Feature | Description |
|---|---|
| **File tree** | Expand/collapse with lazy loading; expansion state is kept across sessions |
| **File operations** | Create, rename, delete, move, copy and numbered duplicate, all from a long-press menu |
| **Git status stripe** | Coloured marker per file/folder — modified, added, untracked, conflicted |
| **Open with Kodex** | Appears in other apps' "Open with" for 145 file extensions and well-known names (`LICENSE`, `NOTICE`, `.gitignore`, `.editorconfig`, …), also inside dotted folders like `.Modxzone` |
| **Open Project sheet** | Search, type a path by hand, or browse folders with editable shortcuts |
| **Recent projects** | Home and *All projects* lists with search and sort. Per-project menu: **pin to top**, **rename**, **delete** (with confirmation), **project info** (size, files, last modified/opened, Git branch), copy path, remove from recents |
| **Backup as ZIP** | One-tap project backup from the recents menu |
| **Pinned files** | Pin files and folders to the top of a project's tree |
| **Long names** | The file tree scrolls sideways only while a name is wider than the screen (Settings → Behavior → *Scroll long file names*, on by default) |
| **Safety** | Binary/large-file open warning, hidden-files toggle, and device root, Documents and Downloads can never be deleted from the app |

</details>

<details>
<summary><b>📝 Editor</b></summary>

| Feature | Description |
|---|---|
| **Engine** | [Sora Editor](https://github.com/Rosemoe/sora-editor) wrapped in Compose, with TextMate syntax highlighting |
| **Tabs** | Multi-tab with reorder, unsaved indicator and close others/all; zoom, cursor, scroll and undo stack are kept per tab |
| **Editing** | Find/replace (case, whole word, regex), go to line, undo/redo, auto-indent, bracket matching, word wrap, font zoom |
| **Autocomplete** | Keyword and word based — no language server needed |
| **Symbol bar** | Customisable, add/remove/reorder |
| **Quick actions** | Duplicate/delete/move line, comment toggle, select line |
| **External changes** | "Reload / keep my edits" prompt when a file changes outside the app |
| **Large files** | Paged loading with a read-only fallback |
| **Languages** | C, C++, CSS, Dart, Groovy, HTML, Java, JavaScript, JSON, Kotlin, Markdown, Properties, Python, Shell, Smali, TOML, XML, YAML — everything else opens as plain text |

</details>

<details>
<summary><b>🌳 Git (JGit — HTTPS + token)</b></summary>

| Feature | Description |
|---|---|
| **Core operations** | Clone with live progress, status, stage/unstage, commit (+ amend), push/pull/fetch, force push (incl. force-with-lease) behind a confirmation |
| **Branches** | List, create, checkout, delete, rename, quick-switch dropdown — also before the first commit |
| **Rebase** | Rebase onto a branch with continue / skip / abort and conflict resolution |
| **History** | Log, commit detail, file history, search by message/author/hash |
| **Diff** | GitHub-style unified and side-by-side viewer with word highlights, tap-to-open, inline editing, image diff preview |
| **Merge & cherry-pick** | Fast-forward and normal merge, cherry-pick from history |
| **Conflicts** | Inline accept ours/theirs/both, abort or complete the merge |
| **Stash & tags** | Save/list/apply/pop/drop and tag list/create/delete |
| **Guided onboarding** | Step-by-step wizard: init → identity → remote → first commit → upstream (skippable) |
| **Multi-remote** | Per-remote token manager; tokens live in an AES/GCM vault backed by the Android Keystore |
| **GitHub settings** | Per-host token and profile management |

Not planned: GPG commit signing, submodules, SSH auth.

</details>

<details>
<summary><b>👀 Preview & search</b></summary>

| Feature | Description |
|---|---|
| **Preview** | Markdown (theme toggle), HTML (split mode, JS off by default), SVG, images and video as in-editor tabs |
| **Vector XML preview** | Any `.xml` with a `<vector>` root gets the same Editor / Split / Preview toggle. Live render (groups, clip-paths, strokes, alpha), light/dark canvas switch, and **PNG**, **SVG** and **Copy SVG** buttons — exports land in `Downloads/` |
| **File search** | Fuzzy filename match, recents, extension/folder filters. Long-press a result for **Show in tree** |
| **Code search** | Whole-workspace content search with regex/case/whole word and streamed, grouped results. Long-press a file for *Locate in file tree* or **Exclude this file** (saved per project, restorable from the *Excluded files* list) |
| **Search history** | Separate history for file and code search |

</details>

<details>
<summary><b>🎨 Themes & diagnostics</b></summary>

| Feature | Description |
|---|---|
| **Material 3 Expressive UI** | Jetpack Compose throughout, with expressive motion and pill-shaped components |
| **5 app themes** | Default, Aurora, Catppuccin, Nord and Rose Pine, plus an **AMOLED** true-black toggle |
| **Editor themes** | Light/dark pairs including One Dark Pro, GitHub, Solarized, Monokai, Ayu, Darcula, VS Code and Kodex |
| **Crash & Git logs** | On-device crash log and Git log viewers under Settings → Diagnostics |
| **About** | Version, GitHub and Libraries buttons, Support, GitHub contributors and the AGPL-3.0 license under Settings → About |

</details>

---

## 🛠️ Tech stack

| Layer | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3, Navigation Compose |
| Editor | [`io.github.Rosemoe:editor`](https://github.com/Rosemoe/sora-editor) + TextMate grammars |
| Git | [`org.eclipse.jgit`](https://www.eclipse.org/jgit/) |
| Async | Coroutines + Flow |
| Storage | Room (recent projects, pins, search history), DataStore (settings) |
| Token security | Android Keystore (AES/GCM) |
| Images | Coil |

---

## 📁 Project structure

```
app/src/main/java/com/invictus/kodex/
 ├─ core/
 │   ├─ fs/          # file operations, directory/external-change watchers, storage permission
 │   ├─ git/         # JGit session, auth, cloner, conflict parser
 │   ├─ github/      # GitHub profile + contributors lookups
 │   ├─ editor/      # tab buffers, language registry, TextMate + editor settings
 │   ├─ preview/     # preview helpers
 │   ├─ search/      # fuzzy file search, code search, search history
 │   ├─ project/     # recents + pins repository, ZIP backup
 │   ├─ security/    # per-remote token vault
 │   ├─ data/        # Room database, DAOs, entities
 │   └─ diagnostics/ # crash + Git logs
 ├─ feature/
 │   ├─ home/        # home, all-projects list, project card menu
 │   ├─ project/     # Open Project sheet
 │   ├─ workspace/   # file tree, workspace screen
 │   ├─ editor/      # tabs, editor screen, find/replace, symbol bar
 │   ├─ git/         # status, commit, branches, diff, history, stash, remotes, onboarding
 │   ├─ preview/     # Markdown / HTML / SVG / image / video
 │   ├─ search/      # file search overlay, code search screen
 │   ├─ settings/    # appearance, editing, behaviour, GitHub, diagnostics, about + libraries
 │   ├─ permission/  # onboarding + storage permission flow
 │   └─ diagnostics/
 ├─ ui/              # theme, shared components, navigation
 └─ MainActivity
```

**Pattern:** MVVM + unidirectional data flow (`UiState` / events / effects). All file-system and JGit
work runs on `Dispatchers.IO`, never the main thread.

---

## 🧩 APK variants

Kodex ships one app, split per CPU architecture so you only download the native libraries your phone
can actually use:

| APK | For |
|---|---|
| `kodex-<tag>-arm64-v8a.apk` | Almost every phone from the last decade — **pick this one** |
| `kodex-<tag>-armeabi-v7a.apk` | Older 32-bit ARM devices |
| `kodex-<tag>-universal.apk` | Both ARM ABIs in one file — larger, use it if you're unsure |

x86 / x86_64 builds are intentionally not produced.

---

## 🔨 Building

Requires JDK 17 and the Android SDK (compileSdk 36, minSdk 30).

```bash
./gradlew assembleDebug      # debug APK (installs next to release as com.invictus.kodex.debug)
./gradlew assembleRelease    # unsigned release APKs, one per ABI + universal (sign with apksigner)
./gradlew lintDebug          # Android lint
```

Or open the project in Android Studio and run it normally.

### Signing a release build

Release builds are intentionally unsigned by Gradle — `assembleRelease` produces unsigned APKs which
you sign explicitly with `apksigner`:

```bash
apksigner sign --ks your-release.jks --ks-key-alias <alias> \
  --out kodex-arm64-v8a.apk app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
```

CI does this for you through [`.github/scripts/sign-apks.sh`](.github/scripts/sign-apks.sh). Every push
to `main` runs `.github/workflows/android-build.yml` and uploads the signed APKs as build artifacts
(fork PRs have no secrets, so their APKs stay unsigned). It needs these repository secrets:

| Secret | Purpose |
|---|---|
| `KEYSTORE_BASE64` | Base64-encoded signing keystore |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

---

## 🚀 Releases

Two tag-triggered workflows build signed APKs and publish them to GitHub Releases, with SHA-256
checksums and notes pulled from `CHANGELOG.md`:

- **`.github/workflows/release.yml`** — stable releases, tags matching `vX.Y.Z` (e.g. `v1.1.0`, the current release).
- **`.github/workflows/prerelease.yml`** — pre-releases, tags matching `vX.Y.Z-suffix` (e.g.
  `v1.1.0-beta.1`, `v1.1.0-rc.2`). The GitHub Release is flagged **Pre-release** automatically.

Both fail if any signing secret is missing — a release is never published unsigned.

To cut a release:

1. In `CHANGELOG.md`, rename `## [Unreleased]` to `## [x.y.z] - YYYY-MM-DD` (pre-releases may keep
   `[x.y.z]` or stay under `[Unreleased]`), then commit and push to `main`.
2. Tag the commit and push the tag:

   ```bash
   # stable
   git tag v1.1.0
   git push origin v1.1.0

   # pre-release
   git tag v1.1.0-beta.1
   git push origin v1.1.0-beta.1
   ```

3. The matching job publishes the GitHub Release with all three APKs attached.

There is nothing to bump in Gradle: the tag sets `versionName` (`v1.2.3` → `1.2.3`) and
[`.github/scripts/version-from-tag.sh`](.github/scripts/version-from-tag.sh) derives a `versionCode`
that always increases (`alpha` < `beta` < `rc` < stable of the same version), so a beta updates
cleanly into its final release.

You can also run either workflow by hand from **Actions → Make release / Make pre-release →
Run workflow** and typing the tag name, without pushing a tag first.

---

## 🔐 Permissions

- `MANAGE_EXTERNAL_STORAGE` — "All files access", so Kodex can work directly on your project folders
- `INTERNET` — Git clone/push/pull over HTTPS and GitHub profile lookups
- `POST_NOTIFICATIONS` — clone and sync progress

---

## 🚫 Out of scope

On-device build/run, SDK/Gradle/JDK management, a terminal, SSH auth, LSP servers, AI agents/MCP,
a plugin system and a real Compose/XML/Flutter layout preview.

---

<div align="center">
  <sub>Built with ⚡ by <a href="https://github.com/Utsavrajputt">Utsav</a></sub>
</div>

## M17–M20: AI Assistant (ai flavor only)
- New `ai` product flavor: AI ships as `kodex-ai` (applicationId `.ai`, label "Kodex AI"); the `normal` flavor contains no AI code.
- Providers: Anthropic Claude, OpenAI, Google Gemini — BYOK, Keystore-AES/GCM vault (Settings → AI), manual SSE over OkHttp.
- Editor AI panel (bottom split): streaming chat with file context + token estimate, secret redaction, exclusion globs.
- Inline edit: selection → "Edit with AI" → search/replace proposal → per-hunk diff review → single-undo-step apply with stale-file protection.
- Commit message generation (Git stage), explain-diff (commit detail + diff review), explain-error (Run log, Diagnostics) with optional reviewed fix patch.
- Diagnostics logs redact AI/git tokens. CI builds both flavors: 6 APKs per build (`kodex-normal-*`, `kodex-ai-*`).
