// Runs only against a local test server. Supply MINEFLAYER_MODULE when installed elsewhere.
const path = require('node:path');
const fs = require('node:fs');
const mineflayer = require(process.env.MINEFLAYER_MODULE || 'mineflayer');
const nbt = require(require.resolve('prismarine-nbt', { paths: [path.dirname(require.resolve(process.env.MINEFLAYER_MODULE || 'mineflayer'))] }));
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const port = Number(process.env.PPP_TEST_PORT || 25579);
const username = process.env.PPP_TEST_USER || 'PppSmokeBot';
const bot = mineflayer.createBot({ host: '127.0.0.1', port, username, auth: 'offline', version: process.env.PPP_TEST_VERSION || '26.1', checkTimeoutInterval: 60000 });
let dialogs = [], messages = [], done = false;
function componentLinks(value) {
  if (Array.isArray(value)) return value.flatMap(componentLinks);
  if (!value || typeof value !== 'object') return [];
  const links = value.click_event?.id ? [{ label: plain(value).replace(/^\[|\]$/g, ''), action: { id: value.click_event.id } }] : [];
  return links.concat(componentLinks(value.extra));
}
function plain(value) {
  if (typeof value === 'string') return value;
  if (Array.isArray(value)) return value.map(plain).join('');
  if (!value || typeof value !== 'object') return '';
  return (value.text || value[''] || '') + plain(value.extra || []);
}
bot._client.on('show_dialog', packet => {
  const data = nbt.simplify(packet.dialog.data);
  const cards = [].concat(data.body || []).filter(body => body.type === 'minecraft:item')
    .map(body => ({ body, text: plain(body.description?.contents || body.description), buttons: componentLinks(body.description?.contents || body.description) }));
  const buttons = [...(data.actions || []), ...cards.flatMap(card => card.buttons), ...(data.exit_action ? [data.exit_action] : []), ...(data.action ? [data.action] : [])];
  dialogs.push({ data, cards, buttons, namespace: buttons.find(b => b.action?.id)?.action.id.split(':')[0] });
  console.log('SCREEN', plain(data.title), buttons.map(b => plain(b.label)).join(' | '));
});
bot.on('messagestr', message => { messages.push(message); });
bot.on('error', fail);
bot.on('kicked', reason => { if (!done) fail(new Error('Unexpected kick: ' + JSON.stringify(reason))); });
function fail(error) { if (done) return; done = true; console.error(error.stack || error); bot.quit(); process.exitCode = 1; setTimeout(() => process.exit(1), 250); }
function assert(value, message) { if (!value) throw new Error(message); }
async function waitFor(predicate, label, timeout = 12000) {
  const start = Date.now();
  while (Date.now() - start < timeout) { const result = predicate(); if (result) return result; await delay(40); }
  throw new Error('Timed out: ' + label);
}
async function nextScreen(start, namespace = 'perplayerprofiles') {
  return waitFor(() => dialogs.slice(start).find(dialog => dialog.namespace === namespace), namespace + ' screen');
}
function send(button, value) {
  sendIdentifier(button.action.id, value);
}
function varInt(value) {
  const bytes = []; do { let next = value & 0x7f; value >>>= 7; bytes.push(next | (value ? 0x80 : 0)); } while (value);
  return Buffer.from(bytes);
}
function sendIdentifier(identifier, value) {
  // minecraft-data 26.1 predates the length-prefixed payload in the 26.1.2 server.
  const mcdata = require(require.resolve('minecraft-data', { paths: [path.dirname(require.resolve(process.env.MINEFLAYER_MODULE || 'mineflayer'))] }))(bot.version);
  const mappings = mcdata.protocol.play.toServer.types.packet[1][0].type[1].mappings;
  const packetId = Number(Object.entries(mappings).find(([, name]) => name === 'custom_click_action')[0]);
  let payload = Buffer.from([0]);
  if (value != null) {
    const named = nbt.writeUncompressed({ type: 'compound', name: '', value: { value: { type: 'string', value } } }, 'big');
    payload = Buffer.concat([named.subarray(0, 1), named.subarray(3)]);
  }
  const encodedId = Buffer.from(identifier);
  const framedPayload = bot.version.startsWith('26.') ? Buffer.concat([varInt(payload.length), payload]) : payload;
  bot._client.writeRaw(Buffer.concat([varInt(packetId), varInt(encodedId.length), encodedId, framedPayload]));
}
async function click(pattern, value, expectScreen = true) {
  const dialog = dialogs.at(-1);
  const button = dialog.buttons.find(button => pattern.test(plain(button.label)));
  assert(button, 'Missing button ' + pattern);
  return clickButton(button, value, expectScreen);
}
async function clickCard(name, pattern, value, expectScreen = true) {
  const card = dialogs.at(-1).cards.find(card => card.text.includes(name));
  assert(card, 'Missing character/icon card ' + name);
  const button = card.buttons.find(button => pattern.test(plain(button.label)));
  assert(button, 'Missing card action ' + pattern + ' for ' + name);
  return clickButton(button, value, expectScreen);
}
async function clickButton(button, value, expectScreen = true) {
  const start = dialogs.length; send(button, value);
  if (!expectScreen) return;
  const token = button.action.id.split('/')[0];
  return waitFor(() => dialogs.slice(start).find(next => next.namespace === 'perplayerprofiles'
    && next.buttons.some(candidate => candidate.action?.id && !candidate.action.id.startsWith(token + '/'))), 'new screen after ' + plain(button.label));
}
function capture(name) {
  const directory = process.env.PPP_TEST_REPORTS || 'build/reports/profile-ui';
  fs.mkdirSync(directory, { recursive: true });
  fs.writeFileSync(path.join(directory, name + '.dialog.json'), JSON.stringify(dialogs.at(-1).data, null, 2));
}
async function pin(value, expectScreen = true) {
  for (const digit of value) await click(new RegExp('^' + digit + '$'));
  return click(/^Confirm$/, undefined, expectScreen);
}
async function command(text) { bot.chat(text); await delay(350); }
async function authenticate() {
  const auth = await waitFor(() => dialogs.find(dialog => dialog.namespace === 'serverpassword'), 'ServerPassword login');
  assert(!dialogs.some(dialog => dialog.namespace === 'perplayerprofiles'), 'Profiles opened before ServerPassword authentication');
  // Verify that the other plugin ignores actions in our namespace while locked.
  sendIdentifier('perplayerprofiles:invalid/0');
  await delay(100);
  await command('/serverpass 1234'); await delay(1200);
  return auth;
}

bot.once('spawn', () => (async () => {
  await authenticate();
  if (process.env.PPP_TEST_PAGINATION === 'true') {
    let position = dialogs.length; await command('/ppp'); await nextScreen(position);
    const first = dialogs.at(-1).cards.map(card => card.text.split('\n')[0]);
    assert(first.length === 2, 'Configured first page should contain two characters');
    capture('selector-page-1'); await click(/^Next/);
    assert(dialogs.at(-1).cards.length === 1, 'Last page should contain only the remaining character');
    assert(plain(dialogs.at(-1).data.title).includes('2/2'), 'Page count missing');
    capture('selector-page-2'); await click(/^.*Previous$/);
    assert(JSON.stringify(dialogs.at(-1).cards.map(card => card.text.split('\n')[0])) === JSON.stringify(first), 'Previous did not restore the first page');
    console.log('PPP_PAGINATION_OK: configured two-character pages, last page, previous navigation and native head items');
    done = true; bot.quit(); setTimeout(() => process.exit(0), 250); return;
  }
  if (process.env.PPP_TEST_PROGRESS === 'true') {
    const body = () => [].concat(dialogs.at(-1).data.body || []).map(entry => plain(entry.contents)).join('\n');
    async function inspect(profile, blocks, used, recipes) {
      await command('/ppp use ' + profile); await delay(900);
      let position = dialogs.length; await command('/ppp settings ' + profile); await nextScreen(position);
      await click(/^Statistics$/);
      assert(new RegExp('Blocks mined: ' + blocks + '(?:\\n|$)').test(body()), 'Wrong block counters for ' + profile + ': ' + body());
      assert(new RegExp('Items used: ' + used + '(?:\\n|$)').test(body()), 'Wrong item counters for ' + profile);
      assert(new RegExp('Discovered recipes: ' + recipes + '(?:\\n|$)').test(body()), 'Wrong recipe set for ' + profile + ': ' + body());
    }
    await inspect('AlphaHero', 42, 17, 1); await inspect(username, 0, 0, 0); await inspect('AlphaHero', 42, 17, 1);
    console.log('PPP_PROGRESS_OK: recipes and block/item counters survive round trips without leaking into the other character');
    done = true; bot.quit(); setTimeout(() => process.exit(0), 250); return;
  }
  let start = dialogs.length; await command('/ppp'); await nextScreen(start);
  capture('selector');
  const staleRefresh = dialogs.at(-1).buttons.find(button => /^Create profile$/.test(plain(button.label)));
  await click(/^Create profile$/);
  assert(dialogs.at(-1).data.inputs?.[0]?.max_length === 16, 'Profile creation is not a native text form');
  await click(/^Confirm$/, 'Bad Name');
  assert(dialogs.at(-1).data.inputs[0].initial === 'Bad Name', 'Invalid input was lost');
  assert(JSON.stringify(dialogs.at(-1).data.body).includes('name must'), 'Inline name error missing');
  capture('invalid-name');
  await click(/^Confirm$/, 'Alpha');
  await click(/^Profile info$/);
  assert(JSON.stringify(dialogs.at(-1).data.body).includes('Created:'), 'Creation date missing');
  capture('profile-info'); await click(/^Back$/);
  await click(/^Appearance$/);
  await click(/^Profile icon$/);
  assert(dialogs.at(-1).cards.length === 3, 'Icon picker should show several real heads');
  for (let page = 0; page < 2; page++) await click(/^Next/);
  assert(JSON.stringify(dialogs.at(-1).data.body).includes('Baby Fox'), 'Eighth icon missing');
  capture('icons'); await clickCard('Baby Fox', /^Use icon$/);
  await click(/^Appearance$/);
  await click(/^Profile icon$/);
  await click(/^Import custom head$/);
  await click(/^Confirm$/, 'https://example.com/head.png');
  assert(JSON.stringify(dialogs.at(-1).data.body).includes('Invalid texture'), 'Inline texture error missing');
  assert(dialogs.at(-1).data.inputs[0].initial === 'https://example.com/head.png', 'Invalid texture value was lost');
  const texture = 'a95ad4f73f500552b747840db988e7fa313a5afe3784e05655c2a513e8e0b1ca';
  await click(/^Confirm$/, 'https://textures.minecraft.net/texture/' + texture);
  assert(plain(dialogs.at(-1).data.title).includes('preview'), 'Head preview missing');
  await click(/^Confirm$/);
  await click(/^Appearance$/);
  await click(/^Rename profile$/);
  await click(/^Confirm$/, 'AlphaHero');
  await command('/ppp create Bravo');
  start = dialogs.length; await command('/ppp icon Bravo'); await nextScreen(start);
  await clickCard('Baby Fox', /^Use icon$/);
  start = dialogs.length; await command('/ppp settings AlphaHero'); await nextScreen(start);
  await click(/^Security$/);
  await click(/^Lock profile$/);
  await pin('1234');
  capture('locked-profile');
  await click(/^Enter PIN$/);
  await pin('0000');
  assert(JSON.stringify(dialogs.at(-1).data.body).includes('Wrong PIN'), 'Wrong PIN feedback missing');
  capture('wrong-pin');
  const beforeSuccess = messages.length;
  await pin('1234', false);
  await waitFor(() => messages.slice(beforeSuccess).some(message => message.includes('Active profile:') && message.includes('AlphaHero')), 'Profile switch');
  await waitFor(() => dialogs.some(dialog => plain(dialog.data.title) === 'Changing character'), 'Transition animation');
  await delay(900);
  const beforeStale = dialogs.length; send(staleRefresh); await delay(400);
  assert(dialogs.length === beforeStale, 'Stale screen action was accepted');
  await command('/ppp use ' + username);
  await delay(900);
  start = dialogs.length; await command('/ppp'); await nextScreen(start);
  assert(dialogs.at(-1).cards.length === 3, 'All three profiles should be on the same page');
  assert(!dialogs.at(-1).buttons.some(button => /^Next/.test(plain(button.label))), 'Pagination should be hidden when all profiles fit');
  for (const card of dialogs.at(-1).cards) {
    assert(card.body.item.id === 'minecraft:player_head', 'Profile icon is not a real player head item');
    assert(card.body.show_tooltip !== 0, 'Head hover details missing');
    assert(!JSON.stringify(card.body).includes('█'), 'Flat pixel portrait leaked into the real head selector');
  }
  assert(new Set(dialogs.at(-1).cards.map(card => card.body.item.components['minecraft:profile'].properties.find(property => property.name === 'textures').value)).size === 3,
    'Profiles should keep their own custom head textures');
  capture('selector-locked');
  assert(dialogs.at(-1).data.body.some(body => JSON.stringify(body).includes('AlphaHero')), 'Renamed character missing from selector');
  await clickCard('AlphaHero', /^Details$/);
  assert(plain(dialogs.at(-1).data.title) === 'AlphaHero', 'Details opened the wrong character');
  await click(/^Back$/);
  await clickCard('AlphaHero', /^Select character$/);
  assert(plain(dialogs.at(-1).data.title).includes('AlphaHero'), 'Character link opened the wrong PIN form');
  await click(/^Cancel$/);
  await click(/^Language$/); capture('languages');
  await click(/^Español$/);
  assert(plain(dialogs.at(-1).data.title).includes('personaje'), 'Per-player language missing');
  start = dialogs.length; await command('/ppp language en'); await nextScreen(start);
  if (process.env.PPP_TEST_ADMIN === 'true') {
    start = dialogs.length; await command('/ppp admin resetpin ' + username + ' AlphaHero'); await nextScreen(start);
    start = dialogs.length; await command('/ppp use AlphaHero'); await nextScreen(start);
    assert(plain(dialogs.at(-1).data.title).includes('New PIN'), 'Admin reset did not request a new PIN');
    await pin('5678', false); await delay(1000);
    await command('/ppp admin removepin ' + username + ' AlphaHero');
    start = dialogs.length; await command('/ppp settings AlphaHero'); await nextScreen(start);
    assert(dialogs.at(-1).buttons.some(button => plain(button.label) === 'Use profile'), 'Admin removal kept the PIN lock');
    await click(/^Statistics$/); capture('statistics'); await click(/^Back$/);
  }
  // This packet is also used by clients opening translated inventory GUIs.
  await command('/ppp mode legacy');
  await waitFor(() => bot.currentWindow?.slots.some(item => item?.name === 'player_head'), 'Legacy inventory heads');
  assert(bot.currentWindow.slots.filter(item => item?.name === 'player_head').length >= 2, 'Legacy selector lost profile icons');
  bot.closeWindow(bot.currentWindow);
  start = dialogs.length; await command('/ppp mode auto'); await nextScreen(start);
  console.log('PPP_SMOKE_OK: auth order, three native 3D heads on one page, per-character links and hover, inline errors, dates, icon pagination/import, PIN retry, transition, language, recovery, stale tokens and legacy mode');
  done = true; bot.quit(); setTimeout(() => process.exit(0), 250);
})().catch(fail));
setTimeout(() => fail(new Error('Global smoke-test timeout')), 120000);
