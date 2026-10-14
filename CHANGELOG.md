# 📜 Changelog

All notable changes to **Kodex** are documented in this file.
The format loosely follows [Keep a Changelog](https://keepachangelog.com/), and versioning follows
[SemVer](https://semver.org/) with pre-release identifiers (`-alpha.N`, `-beta.N`, `-rc.N`) leading up to
stable releases.

When cutting a release, add a `## [x.y.z] - YYYY-MM-DD` section for it; the release workflows
pick the matching section as the GitHub Release notes.

## [1.2.0] - 2026-10-14

**AI & GitHub release.** Kodex is rebranded, gains a native AI agent that works with any provider (Claude, OpenAI, Gemini, OpenRouter, Groq, DeepSeek and more, or your own endpoint) with multi-file editing, and a GitHub Manager that runs workflows and installs your latest build.

### ✨ Highlights
- 🏷️ **Rebranded to Kodex** — new package, identifiers, CI and docs.
- 🐙 **GitHub Manager** — Actions runs, live run logs, workflow dispatch, latest APK card, artifacts, deployments and cleanup.
- 🤖 **AI Agent on the workspace screen** — full-project context, multi-file editing, tool calling, review and undo.
- 📝 **Inline Edit** — selection → "Edit with AI" → diff review → apply.
- 💬 **Claude-style chat** — Markdown, code blocks, `@file` mentions, photo attachments, history per project.
- 🌍 **Universal AI** — presets for OpenRouter, Groq, DeepSeek, Mistral, xAI, Together, Hugging Face and local Ollama, plus custom OpenAI-compatible providers. Model lists load live from each provider.
- 🛠️ **AI status & usage** — request/token tracking, limits and clear rate-limit handling.

### ✨ Added
- 🐙 **GitHub Manager** — open it from the workspace menu: workflows, runs and run logs (copyable), reusing the saved git token. Run a workflow with a branch picker and an inputs sheet parsed from the workflow YAML. Status filter chips (All / Running / Success / Failed / Cancelled), a *View all runs* screen and a live building card with steps.
- 📲 **Latest APK card** — resolves the newest successful ARM64 build, downloads it with speed/ETA, pause/resume and variants, verifies ABI/signature/version, and installs only after an explicit tap. Shows **Install / Update / Open** based on the installed build commit, plus an Installed / Update / Downloaded status pill.
- 📦 **ARM64 builds screen** — recent projects' ARM64 artifacts, newest first, with direct download and install. Artifacts and deployments can be installed, saved to a folder or deleted.
- 🧹 **Cleanup sheet** — runs, deployments and artifacts with filters and a preview; rate-limit-aware deletes with stop and retry; the newest successful build is never preselected. It keeps running in the background.
- 🔔 **GitHub notifications** — live progress and results for download, install, save, cleanup and workflow dispatch, plus push-triggered build notifications that open the repo. Long jobs survive leaving the screen.
- 💾 **Persistent APK downloads** — kept in app storage so a self-update restart needs no re-download (newest 3 kept, older ones removed after 24 hours).
- 🌿 **Make default branch on GitHub** from Branches.
- 🤖 **AI agent & chat** — multi-file tools with review/undo, history and *New chat* in the top bar, model picker in the composer, photo attachments, AI image and app-icon generation, thinking summaries in an expandable timeline, and chat that keeps running in the background with a notification when it finishes.
- ✨ **Quick AI commit message** — tap to generate in your saved style, long-press for style settings.
- 🔎 **Explain from the selection popup** and a per-project AI instructions file.
- 📊 **AI usage in the status card** — daily/monthly requests and tokens per model, custom daily limits, reset countdowns.
- ⏱️ **Clear rate-limit handling** — auto-retries and advice; redesigned AI settings with privacy controls; live Gemini model list.
- 🌐 **AI screens use string resources** — consistent localization.
- 💾 **Export / import settings** (Settings → Advanced) — back up app, editor, GitHub and AI preferences to a file and restore them by category.
- 🧠 **AI project memory & instructions** — per-project `.kodex/memory.md` (the agent saves notes with `save_memory`, auto-condensed), global custom instructions in AI settings and per-project `.kodex/instructions.md`, all editable from a new **Memory & instructions** sheet. Chats can be renamed with a long-press in history; renamed titles stay locked.
- 🌍 **Universal AI providers** — built-in presets for OpenRouter, Groq, DeepSeek, Mistral, xAI, Together AI, Hugging Face and Ollama (local), plus **Add custom provider** (name + base URL + key) for any OpenAI-compatible endpoint.
- 🔄 **Live model lists** — models are fetched from each provider (now also Claude and OpenAI) instead of a hardcoded list, newest first and cached for offline use. A small built-in fallback remains for when the list can't be fetched.
- 🎯 **Quick model picker** — shows 3 featured models (active, recently used, provider picks, newest); the rest are under **All models** with search, in the chat switcher and in AI settings.
- 🔎 **Chat history search** — filter saved chats by title.
- 🏷️ **Release tag picker** — *Make release* / *Make pre-release* now show the expected format (`v1.2.0`, `v1.2.0-beta.1`), next-version suggestions from your repo's tags and a list of existing tags to tap. The tag field is pre-filled and checked before the run starts, so a mistyped tag no longer fails the run.
- 🔀 **Branch switch progress** — a "Switching branch…" dialog on the Git screen and a busy bar on the Branches screen.

### 🔄 Changed
- 📦 **Single APK** — AI is a runtime toggle (*Enable AI*) instead of a separate flavor; AI replaces Refresh in the workspace top bar and a floating AI button sits above Source Control in the editor.
- 🔏 **APK signing** now uses apksigner 34.0.0 with default signature schemes.
- 📥 **Installer** — longer install timeout (3 minutes), the install state is no longer reset on resume, silent updates where allowed, and a clear message when Play Protect blocks the install.
- 🧠 **Memory icon** — Memory & instructions now uses a brain icon instead of the AI sparkles, and the chat top bar order is Memory, History, New chat.
- 🕒 **Chat history** is one newest-first list with the date and time on every chat instead of Today / Yesterday groups.
- 🔌 **Network security** — cleartext HTTP is allowed only for `localhost` / `127.0.0.1` (local model servers); everything else stays HTTPS-only.
- 🤖 Editor AI panel shares the agent engine; Gemini defaults updated to 3.8 Flash / 3.1 Pro.
- 🏠 Home: *Clone* moved into the New project sheet; the header icon opens the ARM64 builds screen.
- ⚙️ **Settings reorganised into clearer groups** — Appearance (themes, font), Editor (typing, symbol bar, preview), Workspace (files & tabs, search), GitHub, AI Assistant, Advanced (git status polling, crash/git logs), About. *Enable AI* moved to the top of AI Assistant settings, and AI settings are grouped into Model & key, Memory & instructions, Editing & privacy, Usage & limits.
- 🔴 **Failure notifications** use a red icon.
- 📊 **GitHub builds** show per-job progress rows and an animated running step.

### ⚡ Performance
- 🔀 **Branch switching is faster** — when the target branch is on the same commit as HEAD (e.g. right after creating a branch) only HEAD is moved, with no working-tree walk; any doubt falls back to the normal checkout. The new branch name shows immediately instead of after the full status scan, and the full scan is skipped when the commit did not change.
- ⚡ **Resolving a single conflict** is as fast as staging, with no progress dialog.

### 🐞 Fixed
- 🤖 Gemini "Test connection" 404s.
- ⌨️ Text fields dropping characters.
- 🐙 Other projects' APKs appearing on the current repo's APK card.
- 🖼️ Logo fallback for projects without a launcher icon.
- 🔔 Notification and signing issues.
- 🏠 "Kodex" wordmark shrinking after navigating back.
- 📊 Download/transfer button overlapping other controls on the GitHub screen.

### 🛠️ Technical
- Build commit baked into `BuildConfig.GIT_SHA` (CI passes `-PgitSha`).
- AES/GCM Keystore token vault.
- GitHub API layer with ETag caching; unit tests for the workflow-input parser, APK resolver, cleanup planner and install decisions.

## [1.1.0] - 2026-10-09

🔎 **Search & polish release.** Code search gets cleaner result cards and a persistent per-project
exclude list, "Open with Kodex" finally works for files in dotted and nested folders, and the Home
banner gets a livelier glass-panel animation.

### ✨ Highlights
- 🚫 **Exclude this file** in code search — saved per project, survives app restarts, easy to undo.
- 👆 **Long-press file menus** in code search and file search (**Show in tree**).
- 📂 **Open with Kodex** now matches `.releaserc`, `NOTICE`, `LICENSE`, `.gitignore`, `.editorconfig` and friends, including files inside folders like `.Modxzone`.
- 🏠 Livelier **Home hero** animation.

### ✨ Added
- 🚫 **Exclude this file** — long-press a file card in code search to drop it from every search in that project. The choice is stored per project (it stays until you remove it or clear app data) and is independent of search history. A small 3-dot menu appears next to the option chips once something is excluded, with an **Excluded files (N)** list where each file can be included again. English and Hindi strings.
- 🌳 **Show in tree** — long-press any result or recent file in file search to reveal it in the project tree. Works from the workspace and from the editor (it returns to the workspace and reveals the file).
- 📂 **Wider "Open with" coverage** — 145 extensions (everything the editor highlights, plus common config / build / web / scripting types) and 40 well-known extensionless names (`README`, `LICENSE` / `LICENCE`, `NOTICE`, `CHANGELOG`, `Dockerfile`, `Makefile`, `CODEOWNERS`, `Jenkinsfile`, …), including hidden files such as `.gitignore`, `.gitattributes`, `.releaserc`, `.npmrc` and `.editorconfig`.
- 🏠 **Home hero polish** — soft glow and ground shadow behind the glass panels, a rim light on every panel edge, a diagonal sheen that sweeps across the panels, a scan line on the front screen, softly pulsing tinted window dots, a double-glow `</>` and a staggered rise-in when the screen opens. Everything is drawn in the draw phase, so it costs no recomposition.

### 🎨 Changed
- 🔎 **Code search result cards** — the per-file 3-dot button is gone; **long-press the file header** for *Locate in file tree* and *Exclude this file*. The dismiss cross is now smaller, red and sits at the top of the card, and the folder path runs on its own full-width line under the header so long paths get the whole card (it still glides when it does not fit).
- 📜 **Code search list position** — a new query or a changed option (regex / case / whole word) starts the list from the top; returning from an opened result keeps your place.
- 🌿 **Branch menu** — *Make default on GitHub* is now just **Set default**.
- 🗂️ **File tree menu** — the compact Pin / Cut / Copy icon row is only used for folders; files get normal one-per-row items.
- 📲 **Manifest** — VIEW and EDIT now share the same intent filters (fewer, smaller filters); `MAIN` / `LAUNCHER` is untouched.
- 🏷️ Version bumped to **1.1.0** (`versionCode` default `1010099`; tagged builds still derive both from the tag).

### 🐞 Fixed
- 📂 **"Open with Kodex" missing for many files** — Android's glob matcher does not backtrack, so `.*\.kt` only matched paths with a single dot and `.*/LICENSE` only matched a file in the root. Files under dotted folders (for example `/Documents/.Modxzone/…`) and extensionless names in nested folders never listed Kodex. Each extension is now declared for paths with 1–4 dots and each well-known name for 1–12 path segments (the same approach Markor uses).
- 🔎 Code search results sometimes opening in the middle of the list, scrollable both up and down.

### 🗑️ Removed
- 🌿 *Pull* and *Pull (rebase)* from the local branch menu (pull stays available from the Git screen).
- 🔎 The include / exclude glob fields and *Clear filters* from the code-search options panel — replaced by the per-file exclude above. Old history entries no longer restore invisible globs. The *Extra excludes* setting under Settings → Search still applies.
- 🔎 The per-file 3-dot button in code search results (replaced by long-press).

## [1.0.0] - 2026-10-05

🎉 **First stable release.** Everything below ships in 1.0.0.

### ✨ Highlights
- 📝 Sora-based multi-tab editor with TextMate highlighting, find/replace, symbol bar and autocomplete.
- 🌳 Native Git on JGit — clone, commit, branches, history, GitHub-style diff, merge, rebase, cherry-pick, stash, tags and conflict resolution.
- 📂 Project-first workflow: Home with hero banner, pin / rename / delete / info / backup per project, Open Project sheet with browse shortcuts.
- 👀 Markdown / HTML / SVG / image / video / Android Vector XML previews and fuzzy file + full-workspace code search.
- 🎨 Material 3 Expressive UI with five themes, an AMOLED mode and editor theme families.
- ℹ️ New **About** screen and a **Scroll long file names** setting for the file tree.
- 🚀 Tag-driven CI: signed per-ABI APKs, SHA-256 checksums and notes from this changelog.

### ✨ Added

#### 📂 Projects & files
- 🏠 **Home screen** with an animated hero banner (floating glass panels), recent projects, a New Project sheet and a dedicated **All projects** screen with search and sort.
- 📌 **Pin projects to the top** of recents (Home, All projects and the Open Project sheet). Pinned projects survive the 50-entry recents trim and stay on top under every sort order.
- ✏️ **Rename project folders on disk** straight from the card menu; recents, pins and the open workspace follow the new path.
- 🗑️ **Delete project folders** behind a confirmation dialog. The project disappears from recents, its pins are dropped, and if it was the open workspace the app falls back to the device root. The device root, Documents and Downloads are protected and can never be deleted from here.
- ℹ️ **Project info dialog** — full path, size, file count, last modified, last opened and the Git repository / current branch (or detached commit). Folders over 200 000 files are reported as `N+` instead of stalling.
- 📋 **Copy path** on project cards, with a snackbar confirmation below Android 13.
- 🗂️ **Open Project sheet** with search, typed paths, recursive folder search across internal storage, editable **Browse shortcuts** (show / hide, reorder, custom folders), a persisted *Show hidden* toggle and **Back up as ZIP** for recent projects.
- 📂 **File tree** with lazy loading, persisted expansion, file operations (create / rename / delete / move / copy / numbered duplicate), pinned files, a Git status stripe and a persisted *Show hidden files* toggle (eye icon).
- ↔️ **Scroll long file names** (Settings → Behavior, on by default) — the file tree scrolls sideways as one block, but only while some visible name is wider than the screen; otherwise it stays vertical-only. Turning it off brings back the ellipsised names.
- 🔁 Workspace top bar with a project-name chip dropdown (switch among recents) and external change watcher for files modified outside the app.
- 🧭 Onboarding stepper for storage access and notifications.

#### 📝 Editor
- 🗂️ Multi-tab editor with per-tab state, tab reorder, pin, close others / all, unsaved dialog and **session restore** (*Restore open files* setting).
- 🔎 Find/replace (case / whole word / regex) with match flash, go to line, auto-indent, bracket matching, word wrap, pinch-to-zoom (font slider 20–64 px).
- ⌨️ Customisable **symbol bar** (hidden by default, toggle in Settings) with pair-cursor placement.
- ⚡ **Quick actions** menu: select all, cut / copy / paste, duplicate / delete / move line, comment toggle (incl. XML / CSS block comments), **Copy file content**.
- 💡 Keyword, word and learned-word autocomplete; custom text-selection popup with labels; undo / redo glow; stronger selection highlight.
- 🔄 External-change prompt (*Reload / keep my edits*) with an optional auto-reload mode; paged loading for large files; an *Auto-preview* setting for Markdown / HTML.
- 🎨 TextMate syntax highlighting for C, C++, CSS, Dart, Groovy, HTML, Java, JavaScript, JSON, Kotlin, Markdown, Properties, Python, Shell, Smali, TOML, XML and YAML, with light/dark editor theme pairs and theme families (One Dark Pro, GitHub — the default —, Solarized, Monokai, Ayu, Darcula, VS Code, Kodex).
- 📌 Fixed, swipeable editor top bar with pinned search / save.

#### 🌳 Git (JGit, HTTPS + token)
- ⬇️ Clone with Termux-style live progress (remote lines, MiB, speed, scrolling log) and a private-repo token prompt; status, stage / unstage, commit (+ amend), push / pull / fetch (all remotes, stale remote-tracking refs pruned), discard changes.
- 🚀 Force push with confirmation, **force-with-lease**, pull-rebase, a push-rejected dialog, and push / pull intent preserved across a token-save detour.
- 🌿 Branches (also before the first commit), quick-switch dropdown, redesigned shared **merge / rebase branch picker**, tags, stash, multi-remote manager and cherry-pick.
- 🔀 **Merge** with file-level and block-level conflict resolution, *Mark all resolved*, conflict cards, shared rebase / merge banner, abort / complete; **Rebase** onto a branch with continue / skip / abort.
- 🕘 History and file history with search, **commit detail** screen (author change, hash copy, author grouping).
- 🧾 **GitHub-style diff viewer**: unified and side-by-side views, old / new gutters, hunk headers, word highlights, `+N / −M` stats, GitHub light / dark palette; tap a change to open its diff, pinch-zoom and edit inline with diff tint; image diff preview.
- 🪄 Commit box with paste button and *auto add / commit / push*; source-control drawer and bottom sheet with a badge; guided **Git onboarding** wizard (init → identity → remote → first commit → upstream).
- 🔐 Per-remote token vault (AES/GCM key in the Android Keystore), global GitHub settings with profile cards (24 h refresh, 72 h avatar cache, identity auto-fill).
- 🕒 *Git status detection* toggle (off by default) for the file-tree stripes, and per-operation **Git logs** with wait / run timings.

#### 🔎 Search
- 🔍 **File search** (fuzzy, recents, extension / folder filters) and **code search** (regex, case, whole word, include / exclude globs, parallel scan, streamed grouped results, a `text//name` file filter) with separate histories and a collapsible filters panel. Result headers show the **file name** with the full folder path as a small subtitle that slowly glides to the end and back when it is wider than the row.
- 🗂️ Recursive filename search inside the workspace tree and *locate in file tree*.
- ⚙️ Search settings: default-strings-only (skip `values-*`), extra excludes and a max file size.

#### 👀 Preview
- 📄 Markdown, HTML (split mode, JS off by default), SVG, images and video as in-editor tabs.
- 🤖 **Android Vector XML preview**: `.xml` files with a `<vector>` root open with the Editor / Split / Preview toggle and render live (groups, clip-paths, strokes, fill / stroke alpha, `#AARRGGBB`). Buttons save a **PNG** (1024 px long side) or **SVG** into `Downloads/` (never overwriting) or copy the SVG text.

#### 🎨 UI & settings
- 🎨 **Material 3 Expressive** conversion: Material Symbols Rounded icons, expressive motion, system-bar tint, splash match, five app themes (Default, Aurora, Catppuccin, Nord, Rose Pine) and a true-black AMOLED toggle.
- ⚙️ **Settings** with category cards (Appearance, Editing, Behavior, Diagnostics, GitHub, About).
- ℹ️ **About** screen — animated identity card with version, GitHub and Libraries buttons, Support (UPI), GitHub contributors (cached 72 h) and the AGPL-3.0 notice, plus a **Libraries** screen listing what Kodex is built on. English and Hindi strings.
- 🩺 On-device crash handler and crash / Git log viewers under Settings → Diagnostics.

#### 🛠️ CI & docs
- 🛠️ GitHub Actions workflows: **Android build** (every push to `main` / PR), **Make release** (`vX.Y.Z` tags) and **Make pre-release** (`vX.Y.Z-suffix` tags), all with a manual *Run workflow* option.
- 📄 README and this changelog.

### 🎨 Changed
- 🏷️ **Versioning is driven by the tag**: `v1.2.3` sets `versionName` to `1.2.3` and `versionCode` is derived from the tag (`.github/scripts/version-from-tag.sh`) instead of the CI run number, so pre-releases and stable builds always compare correctly and a beta updates into its final release. Local builds default to `1.0.0`.
- 🧱 Release signing and note generation live in `.github/scripts/` (`sign-apks.sh`, `release-notes.py`), shared by every workflow. Stable and pre-release builds refuse to publish unsigned APKs.
- 🗄️ Room database is at version 3 (adds the `pinned` column to recent projects; existing data is migrated in place).
- 🧼 **UI polish**: compact project card menu with icons, Home search only with more than 9 recents, a folder-download Clone icon, borderless paste button, hero banner without an outline, one shared spacing rhythm, a top bar that matches the page colour, content-height Source Control sheet, larger back icons and a *Reset* that defaults to hard.
- ⚡ **Git performance**: one shared `GitSession` across screens, tuned JGit `WindowCache`, batched resolve / stage, per-path status patching instead of full refreshes, and a progress dialog for long operations.
- 🪶 JGit is pinned to **5.13.5 LTS** (7.x crashed with `readNBytes` on Android < 13).
- 📦 CI and releases produce three APKs: `arm64-v8a`, `armeabi-v7a` and `universal`.

### 🐞 Fixed
- 💥 Crash in file search (`FileSearchViewModel` factory) and duplicate `LazyColumn` keys in the Git change list.
- 📱 Status-bar overlap in the file search overlay; keyboard reappearing after dismissing the Open Project sheet.
- 🧭 Opening a file from search or Git bouncing back to the previous screen (the editor was navigated to before the tab existed).
- 🔎 Find crash and freeze on very large files (debounced, count capped and run off the main thread).
- 🪟 Diff viewer dropping added lines for modified rows and treating removed `--` lines as file headers.
- 🎚️ Font slider now clamps to the pinch-zoom range; theme resources no longer contain `--` inside XML comments.

### 🗑️ Removed
- 🚫 **x86 and x86_64 builds** — no APKs are built, uploaded or released for them, and the matching ABI splits are gone from `app/build.gradle.kts`.
- 🔋 The battery-optimisation step from onboarding and the *Go to file* entry from the overflow menus.
- 🗺️ The milestone checklist from the README (replaced by this changelog).
- 🔖 The old single combined build/release workflow and its `v*` tag trigger in `android-build.yml`.


