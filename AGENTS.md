# Agent guidelines

Most of the code in this repository is written by AI coding agents. Read this first.

## Ground rules

- Everything in English: code, identifiers, comments, docs, commits and issues. Only `android/app/src/main/res/values-it/strings.xml`, `site/src/i18n/it.json` and test assertion literals are Italian.
- Every new user-facing string in `android/` or `site/` needs its English and Italian translations in the same change (`values/strings.xml` and `values-it/strings.xml`; `site/src/i18n/en.json` and `it.json` with the same keys and the app's Italian terms).
- Read [`docs/design.md`](docs/design.md) before starting. If a change contradicts a decision there, update the design doc in the same change and explain why.
- Never commit image data or anything derived from personal images (embeddings, thumbnails, label files). Keep it under `lab/data/`, which is ignored by git.
- Never write code that deletes files without going through the trash abstraction.
- Prefer `adb.exe` over `adb` when connecting to the phone, where available, since it is faster.

## Architecture rules

- Detectors emit `Signal`s and never decide on their own. Ranking lives in the `Ranker`.
- Recall over precision: no detector or ranker may drop a candidate because of protection signals. Protection signals only change the ordering.
- Photo and video age must not be used as a deletion signal.
- Keep the Python lab (`lab/`) and the Android app (`android/`) structurally parallel: same component names, same interfaces.

## Checks

All checks must pass locally before a PR is ready (CI runs `lab`, `android`, `parity`, `site` and `commit-format`):

- `lab/`: `uv run ruff check && uv run ruff format --check && uv run pytest`
- `android/` (JDK 21): `flock /tmp/cribrum-gradle.lock ./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug :baselineprofile:compileNonMinifiedReleaseKotlin` (`./gradlew ktlintFormat` fixes formatting). Always run in one `flock` call so worktrees share one queue; if a run exceeds 30 minutes, check `ps` and `uptime` for a stuck build.
- `site/` (Node from `site/.nvmrc`): `npm ci && npm run check` (runs `build`, `check:i18n`, `check:links` and `check:contrast`).
- `python3 lab/scripts/check_parity.py`: checks that `lab/` and `android/` agree on detector ids, versions, media types, thresholds and constants. Bump the detector version if its output changes.

## Skills

Project skills live in [`.agents/skills/`](.agents/skills/) (`.claude/skills/` holds symlinks). Read a skill's `SKILL.md` before the task it covers.

| Skill | Use it for |
|---|---|
| `tdd` | Building detectors and other features test-first |
| `ponytail` | Keeping a change to the smallest thing that works |
| `code-review` | Reviewing a branch against these guidelines and its GitHub issue |
| `code-simplification` | Simplifying working code without changing behavior |
| `deslop` | Removing AI boilerplate from code before committing |
| `unslop` | Removing AI tells from docs, issues and commit messages |
| `grilling` | Stress-testing a design decision before it goes into `docs/design.md` |
| `ui-council` | A UI/UX review of the Android app by two models, on the UI gallery |

`ui-council` is edited in this repository. The others come from [Bert0ns/agent-skills](https://github.com/Bert0ns/agent-skills), pinned in `skills-lock.json`; update them with `npx skills update -p` and never edit them in place.

## Which model does the work

| Tier | Models | Use them for |
|---|---|---|
| Workers | Sonnet 5.5 high, Gemini 4 Hydrogen high | Most features and fixes, UI changes, tests and docs |
| Strong | Opus 5.5 medium or high, Gemini 4 Argon medium or high, Gemini 4 Neon medium or high | The ranker and its scoring, concurrency and the scan worker, database migrations, design decisions, performance work whose output must not change, and tasks a worker got wrong |

- Gemini 4 Hydrogen high gets simpler worker tasks than Sonnet 5.5 high. Opus 5.5 medium fits moderate logic changes, and Opus 5.5 high, Gemini 4 Argon or Gemini 4 Neon the hardest ones. Heavy phone work (installing builds, driving the app, measuring) needs at least Opus 5.5 medium.
- Reviews use `code-review` with a different model from the author (another provider when possible), at the tier that matches the change's difficulty. Editing `docs/design.md` alone takes a worker; a new design decision takes a strong model. Re-checking review fixes takes a worker. If a model is out of credits, pick another in the same tier.
- In T3 Code, start each issue as its own top-level thread in a worktree, not a subagent or delegated task. Reviews and review-fix checks can run as delegated tasks.

## Commits and pull requests

Every commit subject and PR title must pass `commit-format`: `type(scope): subject` (at most 72 characters, imperative, lowercase after the colon, no trailing period; `!` before `:` for breaking changes).

- Types: `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `build`, `ci`, `chore`.
- Scopes: `android`, `lab`, `site`, `skills` (omit when a change spans the repo).
- Body: explain why, and follow `unslop` (plain words, no em dashes).
- Small PRs squash merge, medium ones rebase merge, and large ones merge commit, so every commit in a PR must follow the format.

## Layout

- `lab/`: Python 3.12+, `src/` layout, tests under `lab/tests/` (pytest).
- `android/`: Kotlin + Jetpack Compose, tests under `android/app/src/test/` (JUnit and Robolectric). See [`android/README.md`](android/README.md).
- `fixtures/`: golden test images and videos shared by the lab and Android tests.
- `site/`: Astro static site (`/` in English, `/it/` in Italian, strings in `site/src/i18n/`). Read [`site/VISUAL-SYSTEM.md`](site/VISUAL-SYSTEM.md) before styling.
