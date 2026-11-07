/** Execute the generated public-page script against a local DOM, without a browser or server. */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';
import { build } from 'esbuild';
import { parseHTML } from 'linkedom';

const bundle = await build({ entryPoints: ['src/page.ts'], bundle: true, write: false, format: 'esm', target: 'es2022' });
const { pageHtml } = await import('data:text/javascript;base64,' + Buffer.from(bundle.outputFiles[0].text).toString('base64'));
const html = pageHtml();
const script = /<script>([\s\S]*?)<\/script>/.exec(html)[1];
const part = (id, available = true) => ({ id, name: id + '.m4a', durationMs: 120_000, available });
const seg = (partId, start, end, text, words = null) => ({ partId, sessionStartMs: start, sessionEndMs: end, text, words, speaker: null });
function session(segments, text, extra = {}) {
  return { id: 'lesson', title: 'Lesson', parts: [part('one')], raw: { model: 'stt', text, segments, wordsEstimated: false }, refined: null, ...extra };
}
async function render(lesson) {
  const { document, window } = parseHTML(html), audios = [], requests = [];
  function Audio() {
    const audio = document.createElement('audio');
    Object.assign(audio, { currentTime: 0, paused: true, readyState: 1, playbackRate: 1,
      load() {},
      play() { this.paused = false; this.dispatchEvent(new window.Event('play')); return Promise.resolve(); },
      pause() { this.paused = true; this.dispatchEvent(new window.Event('pause')); },
    });
    audios.push(audio); return audio;
  }
  runInNewContext(script, { document, location: { pathname: '/s/public-token' }, Audio,
    requestAnimationFrame: () => 1, cancelAnimationFrame() {},
    fetch: async (url, options) => {
      requests.push({ url, options });
      return { ok: true, json: async () => ({ title: 'Shared note', tags: [], body: '', sessions: [lesson], sources: [] }) };
    },
  });
  await new Promise(setImmediate); // Settle the production fetch/json/render promise chain.
  assert.equal(requests.length, 1); assert.equal(requests[0].url, '/s/public-token/data');
  assert.equal(requests[0].options.credentials, 'omit');
  const root = document.getElementById('root');
  assert.ok(root.textContent.includes('Shared note'), root.innerHTML);
  return { root, audio: audios[0], click: element => element.dispatchEvent(new window.Event('click', { bubbles: true })),
    timeupdate: () => audios[0].dispatchEvent(new window.Event('timeupdate')) };
}
function assertUntimed(root) {
  assert.equal(root.querySelectorAll('.para, .t, .w, .silence, .now, .done').length, 0);
  const raw = root.querySelector('.card.notes.static');
  assert.ok(raw); assert.ok(raw.textContent.includes('non ha tempi allineati'));
  return raw;
}
function checkPlayer(view) {
  const { root, audio, click, timeupdate } = view;
  assert.ok(root.querySelector('.player')); assert.ok(audio.src.endsWith('/audio/one'));
  click(root.querySelector('.player .play')); assert.equal(audio.paused, false);
  click(root.querySelector('.player').querySelectorAll('button')[2]); assert.equal(audio.currentTime, 15);
  audio.currentTime = 60; timeupdate();
  assert.ok(root.querySelector('.time').textContent.startsWith('1:00 / '));
  click(root.querySelector('.player .play')); assert.equal(audio.paused, true);
}

test('one text-only part renders full raw Markdown without alignment and retains working audio controls', async () => {
  const text = '# Full transcript\n\nFirst **spoken** paragraph.\n\nComplete text beyond segment excerpt.';
  const view = await render(session([seg('one', 0, 0, 'excerpt')], text));
  const raw = assertUntimed(view.root);
  assert.equal(raw.querySelector('h2').textContent, 'Full transcript');
  assert.equal(raw.querySelector('b').textContent, 'spoken');
  assert.ok(raw.textContent.includes('Complete text beyond segment excerpt.'));
  checkPlayer(view); assertUntimed(view.root);
});

test('two text-only parts with session offsets preserve all text without fabricated two-minute silence', async () => {
  const text = 'First part contains speech.\n\nSecond part contains speech.';
  const view = await render(session([seg('one', 0, 0, 'First part'), seg('two', 120_000, 120_000, 'Second part')], text,
    { parts: [part('one'), part('two')] }));
  const raw = assertUntimed(view.root);
  assert.ok(raw.textContent.includes('First part contains speech.'));
  assert.ok(raw.textContent.includes('Second part contains speech.'));
  assert.equal(raw.textContent.includes('silenzio'), false);
  checkPlayer(view); assertUntimed(view.root);
});

for (const untimedFirst of [false, true]) {
  test(`mixed transcript (${untimedFirst ? 'untimed' : 'timed'} first) renders the full raw text without partial alignment`, async () => {
    const segments = untimedFirst
      ? [seg('one', 0, 0, 'Unaligned'), seg('two', 121_000, 122_000, 'Aligned', '0,1000,Aligned')]
      : [seg('one', 1_000, 2_000, 'Aligned', '0,1000,Aligned'), seg('two', 120_000, 120_000, 'Unaligned')];
    const view = await render(session(segments, 'Aligned content and unaligned content.\n\nFull text tail.', { parts: [part('one'), part('two')] }));
    const raw = assertUntimed(view.root);
    assert.ok(raw.textContent.includes('Aligned content and unaligned content.'));
    assert.ok(raw.textContent.includes('Full text tail.'));
    view.audio.currentTime = 121; view.timeupdate(); assertUntimed(view.root);
  });
}

test('raw text with no segment rows is readable and untimed', async () => {
  const view = await render(session([], 'Full text without segment metadata.'));
  assert.ok(assertUntimed(view.root).textContent.includes('Full text without segment metadata.'));
});

test('fully timed transcript keeps timestamps, seek, highlights, real silence and estimated-word notice', async () => {
  const lesson = session([seg('one', 1_000, 2_000, 'First second', '0,500,First\n500,1000,second'),
    seg('one', 65_000, 66_000, 'After')], 'First second After');
  lesson.raw.wordsEstimated = true;
  const view = await render(lesson), { root, audio, click, timeupdate } = view;
  assert.equal(root.querySelectorAll('.para').length, 2); assert.equal(root.querySelectorAll('.t').length, 2);
  assert.equal(root.querySelectorAll('.w').length, 3); assert.equal(root.querySelectorAll('.silence').length, 1);
  assert.ok(root.querySelector('.silence').textContent.includes('1 min di silenzio'));
  assert.ok(root.textContent.includes('tempi delle singole parole sono stimati'));
  const words = root.querySelectorAll('.w');
  click(words[1]); assert.equal(audio.currentTime, 1.5); assert.ok(words[1].classList.contains('now'));
  audio.currentTime = 1.2; timeupdate(); assert.ok(words[0].classList.contains('now')); assert.equal(words[1].classList.contains('now'), false);
  click(root.querySelectorAll('.t')[1]); assert.equal(audio.currentTime, 65);
  assert.equal(root.querySelector('.card.notes.static'), null);
});

test('untimed Markdown escapes HTML payloads instead of creating executable markup', async () => {
  const text = '# <img src=x onerror="alert(1)">\n\n<script>alert("x")</script> & "quoted"\n\n**<svg onload=alert(2)>**\n\n[click](javascript:alert(3))';
  const view = await render(session([seg('one', 0, 0, text)], text));
  const raw = assertUntimed(view.root);
  assert.equal(raw.querySelectorAll('script, img, svg, a, [onerror], [onload]').length, 0);
  assert.ok(raw.textContent.includes('<script>alert("x")</script> & "quoted"'));
  assert.ok(raw.textContent.includes('<img src=x onerror="alert(1)">'));
  assert.equal(raw.querySelector('b').textContent, '<svg onload=alert(2)>');
});

test('raw/refined tabs preserve untimed raw text and do not promise word highlighting', async () => {
  const view = await render(session([seg('one', 0, 0, 'Raw excerpt')], 'Complete raw content.',
    { refined: { model: '<model>', text: '**Refined content.**' } }));
  assert.ok(assertUntimed(view.root).textContent.includes('Complete raw content.'));
  const tabs = view.root.querySelectorAll('.tabs button');
  view.click(tabs[0]); assert.ok(view.root.textContent.includes('Refined content.'));
  assert.equal(view.root.textContent.includes('parole che si accendono'), false);
  view.click(tabs[1]); assert.ok(assertUntimed(view.root).textContent.includes('Complete raw content.'));
  assert.ok(view.root.querySelector('.player'));
});

test('untimed raw content remains readable when audio is unavailable', async () => {
  const view = await render(session([seg('one', 0, 0, 'Speech')], 'All spoken text.', { parts: [part('one', false)] }));
  assert.ok(assertUntimed(view.root).textContent.includes('All spoken text.'));
  assert.equal(view.root.querySelector('.player'), null); assert.equal(view.audio, undefined);
});

test('refined-only sessions remain readable with their no-timing notice and audio controls', async () => {
  const view = await render(session([], '', { raw: null, refined: { model: 'refiner', text: '**Refined only.**' } }));
  assert.equal(view.root.querySelector('.card.notes b').textContent, 'Refined only.');
  assert.ok(view.root.textContent.includes('non ha i tempi')); checkPlayer(view);
});
