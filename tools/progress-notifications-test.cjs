// Run only against a loopback integration fixture. Requires mineflayer via MINEFLAYER_MODULE.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const username = process.env.PPP_TEST_USER || 'PppProgressBot';
if (process.argv[2] === '--prepare') {
  const fixture = path.resolve(process.argv[3]);
  assert(/^server-ip=127\.0\.0\.1\s*$/m.test(fs.readFileSync(path.join(fixture, 'server.properties'), 'utf8')), 'Fixture must bind to loopback');
  const bytes = crypto.createHash('md5').update('OfflinePlayer:' + username).digest();
  bytes[6] = (bytes[6] & 15) | 48; bytes[8] = (bytes[8] & 63) | 128;
  const hex = bytes.toString('hex');
  const uuid = [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join('-');
  const filename = path.join(fixture, 'ops.json');
  const ops = fs.existsSync(filename) ? JSON.parse(fs.readFileSync(filename, 'utf8')) : [];
  fs.writeFileSync(filename, JSON.stringify([...ops.filter(op => op.uuid !== uuid), { uuid, name: username, level: 4, bypassesPlayerLimit: false }], null, 2));
  console.log('Prepared loopback fixture operator', username); process.exit(0);
}

const mineflayer = require(process.env.MINEFLAYER_MODULE || 'mineflayer');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const report = [];
let bot, xp, stopped = false, advancementToasts = [], recipeToasts = 0;
let definitions = new Map(), progress = new Map();
const reports = process.env.PPP_TEST_REPORTS || 'build/reports/progress-notifications';
function done(error) {
  if (stopped) return; stopped = true;
  fs.mkdirSync(reports, { recursive: true });
  fs.writeFileSync(path.join(reports, 'network.json'), JSON.stringify(report, null, 2));
  if (error) console.error(error.stack || error);
  if (bot) bot.quit();
  setTimeout(() => process.exit(error ? 1 : 0), 250);
}
function isDone(id) {
  const definition = definitions.get(id), criteria = progress.get(id);
  return !!definition && !!criteria && definition.requirements.every(group => group.some(name => criteria.get(name) != null));
}
function connect() {
  return new Promise((resolve, reject) => {
    advancementToasts = []; recipeToasts = 0; definitions = new Map(); progress = new Map();
    bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.PPP_TEST_PORT || 25579),
      username, version: process.env.PPP_TEST_VERSION || '26.1', auth: 'offline' });
    bot.on('error', reject); bot.on('kicked', reason => reject(new Error(JSON.stringify(reason))));
    bot.on('messagestr', text => report.push({ chat: text }));
    bot._client.on('experience', packet => { xp = packet.totalExperience; });
    bot._client.on('recipe_book_add', packet => {
      const notifications = packet.entries.filter(entry => entry.flags.notification).length;
      recipeToasts += notifications;
      report.push({ type: 'recipes', replace: packet.replace, entries: packet.entries.length, notifications });
    });
    bot._client.on('advancements', packet => {
      if (packet.reset) { definitions.clear(); progress.clear(); }
      for (const id of packet.identifiers) { definitions.delete(id); progress.delete(id); }
      for (const entry of packet.advancementMapping) definitions.set(entry.key, entry.value);
      const notices = [];
      for (const entry of packet.progressMapping) {
        progress.set(entry.key, new Map(entry.value.map(criterion => [criterion.criterionIdentifier, criterion.criterionProgress])));
        if (!packet.reset && packet.showAdvancements !== false && definitions.get(entry.key)?.displayData?.flags.show_toast && isDone(entry.key)) notices.push(entry.key);
      }
      advancementToasts.push(...notices);
      report.push({ type: 'advancements', reset: packet.reset, show: packet.showAdvancements, notices });
    });
    bot.once('spawn', async () => {
      try {
        await delay(900);
        if (process.env.PPP_TEST_AUTH === 'true') { bot.chat('/serverpass 1234'); await delay(2000); }
        else await delay(1000);
        resolve();
      } catch (failure) { reject(failure); }
    });
  });
}
async function command(text) { bot.chat(text); await delay(800); }
async function silentSwitch(profile) {
  advancementToasts = []; recipeToasts = 0;
  const start = report.length;
  await command('/ppp use ' + profile);
  await delay(700);
  assert.equal(advancementToasts.length, 0, 'Repeated advancements: ' + advancementToasts.join(', '));
  assert.equal(recipeToasts, 0, 'Repeated recipe notifications');
  assert(report.slice(start).some(packet => packet.type === 'advancements' && packet.reset), 'Character did not send a reset snapshot');
  console.log('SILENT CLIENT SWITCH', profile, 'xp=' + xp);
}

(async () => {
  await connect(); await command('/ppp use ' + username);
  await command('/ppp create ToastProbe');
  await command('/advancement revoke @s everything');
  await command('/recipe take @s *');
  advancementToasts = []; recipeToasts = 0;
  await command('/advancement grant @s only minecraft:adventure/kill_all_mobs');
  await command('/advancement grant @s only minecraft:story/mine_stone');
  await command('/recipe give @s minecraft:stick');
  assert(advancementToasts.length > 0 && recipeToasts > 0, 'Genuine notifications must still work');
  const savedXp = xp;
  for (let i = 0; i < 3; i++) {
    await silentSwitch('ToastProbe');
    assert(!isDone('minecraft:adventure/kill_all_mobs'), 'Advancement leaked into the new character');
    await silentSwitch(username);
    assert(isDone('minecraft:adventure/kill_all_mobs'), 'Saved advancement was lost');
    assert.equal(xp, savedXp, 'XP changed during silent restoration');
  }
  bot.quit(); await delay(1800); await connect();
  assert.equal(advancementToasts.length, 0, 'Reconnect repeated advancements');
  assert.equal(recipeToasts, 0, 'Reconnect repeated recipe notifications');
  await silentSwitch(username);
  assert(isDone('minecraft:adventure/kill_all_mobs'), 'Reconnect lost saved advancement');
  assert.equal(xp, savedXp, 'Reconnect repeated advancement XP');
  console.log('PPP_PROGRESS_CLIENT_OK: real login/authentication, genuine notifications, three character round trips, exact progress/XP and reconnect');
  done();
})().catch(done);
setTimeout(() => done(new Error('Progress notification client test timed out')), 80000).unref();
