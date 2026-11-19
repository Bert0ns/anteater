# Visual system

Read this before you style a section of the site. The tokens are in
[`src/styles/tokens.css`](src/styles/tokens.css), the base styles, the header and the footer in
[`src/styles/global.css`](src/styles/global.css). Use the tokens and do not write raw colours, font
sizes or durations in a component.

## Look

Dark and editorial, in the colours of the logo: a near-black page, warm cream type, and the logo's red
dot as the only accent. The dot is the motif. It sits inside the download button, and a demo can use it
as the thing that eats the clutter. There is no light theme.

## Colour

| Token | Value | Use |
|---|---|---|
| `--bg` | `#121619` | The page. It is the logo's tile colour, so the logo blends in. |
| `--surface` | `#1b2024` | Cards, the header bar, the code box |
| `--raised` | `#232a2f` | Hover and pressed states on a surface |
| `--line` | `#2c3338` | Hairlines and card borders |
| `--line-strong` | `#3a4249` | Borders of buttons and inputs |
| `--text` | `#f4f1ea` | Headings and body text |
| `--text-muted` | `#b7b4ab` | Paragraphs and descriptions |
| `--text-dim` | `#8f8e87` | Notes and captions. Only on `--bg` and `--surface`. |
| `--accent` | `#e53935` | The red dot. Shapes and icons, never text. |
| `--accent-text` | `#ff6b66` | Red text: links, step numbers, small marks |

The red dot is 4.3:1 on `--bg`, under the 4.5:1 that text needs, so text never uses it. Use
`--accent-text`. A button with text on a solid red fill would fail too (4.3:1 with dark text), which is
why the primary button is cream with a red dot inside.

`npm run check:contrast` reads the colours from `tokens.css` and fails if a pair drops under WCAG AA
(4.5:1 for text, 3:1 for shapes). Add a new pair to the list in `scripts/check-contrast.mjs` when you
put a colour on a new background.

| Pair | Ratio |
|---|---|
| `--text` on `--bg` | 16.13 |
| `--text` on `--surface` | 14.56 |
| `--text` on `--raised` | 12.90 |
| `--text-muted` on `--bg` | 8.77 |
| `--text-muted` on `--surface` | 7.92 |
| `--text-muted` on `--raised` | 7.02 |
| `--text-dim` on `--bg` | 5.53 |
| `--text-dim` on `--surface` | 4.99 |
| `--accent-text` on `--bg` | 6.54 |
| `--accent-text` on `--surface` | 5.90 |
| `--accent-text` on `--raised` | 5.23 |
| `--bg` on `--text` (primary button) | 16.13 |
| `--accent` on `--bg` (shape) | 4.30 |
| `--accent` on `--surface` (shape) | 3.89 |
| `--accent` on `--text` (the dot in the button) | 3.75 |

The storage map colours each block by hue, so it has no fixed pair. Its `--map-sat`, `--map-light` and
`--map-step` live in `StorageMap.astro`, and `check:contrast` checks the page's dark ink on a block, and
the light ink on a faded block, over twelve hues and five depths.

## Fonts

Both are variable, OFL-1.1, and hosted on the site. Nothing loads from a font CDN.

- **Display**: Bricolage Grotesque, weights 200 to 800. Headings, the brand name, big numbers.
- **Text**: Instrument Sans, weights 400 to 700. Everything else.
- **Mono**: the system monospace stack, for code.

The files come from the `@fontsource-variable` packages and only the Latin subset ships, about 30 and
41 KB. It covers English and Italian, accented letters included. [`fonts.css`](src/styles/fonts.css)
declares them, and `Base.astro` preloads both. Each has a fallback face with the metrics of Arial, so the
text does not move when the font arrives. If you add a language that needs more than Latin, add that
subset's file to `fonts.css`.

## Type scale

Fluid, from a 360 px phone to a 1440 px screen. Use the steps, not pixel sizes.

| Token | Size at 360 / 1440 px | Use |
|---|---|---|
| `--step--1` | 13 / 14 px | Notes, captions, nav, footer |
| `--step-0` | 16 / 18 px | Body |
| `--step-1` | 19 / 22 px | Lead paragraphs, small headings |
| `--step-2` | 23 / 30 px | Subheadings |
| `--step-3` | 29 / 44 px | Section headings (`h2`) |
| `--step-4` | 36 / 64 px | Big statements |
| `--step-5` | 44 / 92 px | The page title (`h1`) |

Headings use the display font with tight letter spacing and `text-wrap: balance`. Body text is left
aligned with `text-wrap: pretty`.

## Spacing and layout

- Spacing is on a 4 px base: `--space-1` (4 px) up to `--space-10` (144 px).
- `.container` is at most `--container` (1200 px) wide with a `--gutter` of 16 px on a phone and 40 px
  on a wide screen.
- Two breakpoints, both `min-width`: `40rem` (640 px, feature rows go to two columns) and `56.25rem`
  (900 px, the header nav replaces the menu, sections get a heading column, the footer goes to three
  columns). Build for 360 px first. A page never scrolls sideways at 360 px.
- Radii: `--radius-s`, `--radius-m`, `--radius-l` and `--radius-pill`. Buttons, the header bar and
  the language switch are pills.

## Header and footer

`Base.astro` renders both, so every page gets them.

- The header is a pill that sticks to the top. It has the logo, the section links, the language
  switch and a Download button. Under 900 px the links move into a `<details>` menu, which works
  without JavaScript. A small script closes it after a tap on a link or on Escape. The switch shows the
  full name on a wide screen and `IT` or `EN` on a phone.
- The section links point at `/#id` or `/it/#id`, so they work from any page. The Features and FAQ
  links point at their own pages and carry `aria-current="page"` on them. The header keeps six links,
  the most that fit in the bar at 900 px with the Italian labels, and the footer has the full list.
  When a sub-issue adds or renames a section, change the `links` list in `Header.astro` and
  `Footer.astro`.
- `Base.astro` takes a `page` (`home`, `features` or `faq`), which sets the canonical URL, the `hreflang`
  tags, the meta strings and the language switch. A new page adds its name to `Page` in `i18n/index.ts`.
- The footer has the logo and a line about the app, two link lists and a bottom row with the licence
  and the privacy line.
- Every string is in `src/i18n/en.json` and `it.json`, under `nav`, `footer` and `a11y`.

## Motion

### Durations

| Token | Value | Use |
|---|---|---|
| `--dur-instant` | 100 ms | Press feedback |
| `--dur-fast` | 180 ms | Hover and focus: colour, border, opacity |
| `--dur-base` | 320 ms | Menus, the dot growing, small movements |
| `--dur-slow` | 560 ms | Scroll reveals and a section entering |
| `--dur-hero` | 1000 ms | The hero demo and other one-time sequences |

Nothing runs longer than `--dur-hero` per step, except an animation tied to the scroll position. A
timed sequence such as the hero demo can chain short steps.

### Easing

| Token | Curve | Use |
|---|---|---|
| `--ease-out` | `cubic-bezier(0.22, 1, 0.36, 1)` | The default. Things that appear or answer the user. |
| `--ease-in` | `cubic-bezier(0.64, 0, 0.78, 0)` | Things that leave the screen. |
| `--ease-in-out` | `cubic-bezier(0.65, 0, 0.35, 1)` | Things that move from one place to another, such as a slider or a view transition. |

Use `linear` only for something that follows the scroll position or a progress bar.

### Properties

Animate `transform` and `opacity`. Do not animate `width`, `height`, `top`, `margin` or anything else
that moves the layout, so the page never shifts. Reserve the space of every element before it
animates.

### Writing an animation

The end state is the normal style. The animation is an addition on top of it, so a browser without
support, without JavaScript, or with reduced motion shows the finished page.

```css
.reveal {
  /* The end state: nothing to do. */
}

@media (prefers-reduced-motion: no-preference) {
  @supports (animation-timeline: view()) {
    .reveal {
      animation: rise var(--dur-slow) var(--ease-out) both;
      animation-timeline: view();
      animation-range: entry 0% entry 40%;
    }
  }
}

@keyframes rise {
  from {
    opacity: 0;
    transform: translateY(1.5rem);
  }
}
```

In JavaScript, check the preference before you start. Without the Web Animations API call the markup
is already the end state.

```js
if (!matchMedia("(prefers-reduced-motion: reduce)").matches) {
  element.animate(keyframes, { duration: 1000, easing: "cubic-bezier(0.22, 1, 0.36, 1)", fill: "both" });
}
```

### What each kind becomes under `prefers-reduced-motion: reduce`

| Kind | Normal | Reduced |
|---|---|---|
| State changes (hover, focus, press, the menu icon) | Transition of `--dur-fast`, or `--dur-base` for the menu icon | The change is instant. Colours still change. |
| Reveal on scroll or on entry | Fade and a short rise, `--dur-slow` | No animation. The element is visible. |
| Scroll-driven motion (parallax, pinned scenes) | Follows the scroll | Not used. The scene shows its end state. |
| The hero demo and other timed sequences | Plays once, up to `--dur-hero` per step | Not started. The demo shows its end state. |
| Direct manipulation (dragging a slider, scrubbing) | Follows the pointer | The same. The user drives it, so it stays. |
| Loops (pulsing, auto-cycling, marquees) | Not allowed. | Not allowed. A demo that needs a repeat gets a play button and a static frame to start on. |
| View Transitions between pages | Cross-fade | A normal page load |
| Smooth scrolling to an anchor | Smooth | Instant |
| Autoplay video | Not used on this site | Not used |

`tokens.css` also has a global safety net for reduced motion: every duration becomes 0.01 ms, loops
stop after one run, and smooth scrolling is off. It does not replace the rules above. A demo still
has to decide what its static end state is.

## The hero demo

`HeroDemo.astro` is a phone in HTML and CSS with a grid of the sample images. A scan runs down it, the
photos it finds are marked by category, the logo's red dot eats them, and a counter climbs. Everything in
it is data in [`src/lib/hero-demo.ts`](src/lib/hero-demo.ts): the tiles, their category, how a source
image becomes its variant, and the size the counter adds for each. Adding images is a change to that list.

- The variants are the same source image with a CSS filter, a crop or a frame (`blur`, `dark`, `bright`,
  `crop`, `screenshot`). There are no extra image files. The filters are fixed and never animated.
- Three tiles per row, and `rows` of them are visible. Flagged tiles go in the visible rows. Tiles after
  them are photos to keep. They sit below the fold and slide up when the dot has eaten the flagged ones.
- The categories and their names are the app's, in `hero.demo.categories`. The legend, the counts and the
  text alternative are built from the data.
- The phone is drawn in `cqw`, a hundredth of its width, so it is one drawing at every size. Its text
  stays at 12 px or more.
- The numbers are made up and the photos are AI generated. The note under the demo says so.

Motion. Each step is one of the duration tokens, up to `--dur-slow`, and the whole sequence is about
six seconds, so the demo is a chain of short animations and not one long one. The script reads the
tokens from the page. It only changes classes and moves things with `transform` and `opacity`. The flagged
tiles stay in the layout, invisible once eaten, and the kept tiles slide over them, so nothing on the page
shifts. It starts when the phone is 60% in view, plays once, and the Replay button runs it again.

The end state is the normal CSS: the flagged tiles are hidden, the legend is lit, and the snackbar is
there. The start state lives in one `@media (scripting: enabled) and (prefers-reduced-motion:
no-preference)` block. Without scripting or with reduced motion the demo is never started, the Replay
button stays hidden, and the page shows the end state.

## Checks

`npm run check` builds both languages, checks that the two string files have the same keys, checks the
internal links, and checks the colour contrast. Before you open a PR, also look at the page at 360 px
in English and Italian. Italian runs longer, and the header and the footer must not scroll sideways.
