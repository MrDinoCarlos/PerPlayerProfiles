// Requires a loopback fixture with ServerPassword PIN 1234 and an operator profile named AlphaHero.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const mineflayer = require(process.env.MINEFLAYER_MODULE || 'mineflayer');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.PPP_TEST_PORT || 25579),
  username: process.env.PPP_TEST_USER || 'PppUiV2Bot', version: process.env.PPP_TEST_VERSION || '26.1', auth: 'offline' });
let transaction = 0, tree, done = false;
const messages = [];
bot._client.on('declare_commands', packet => { tree = packet; });
bot.on('messagestr', message => messages.push(message));
function finish(error) {
  if (done) return; done = true;
  if (tree) {
    const directory = process.env.PPP_TEST_REPORTS || 'build/reports/command-completion';
    fs.mkdirSync(directory, { recursive: true });
    fs.writeFileSync(path.join(directory, 'commands.json'), JSON.stringify(tree, null, 2));
    fs.writeFileSync(path.join(directory, 'messages.json'), JSON.stringify(messages, null, 2));
  }
  if (error) console.error(error.stack || error);
  bot.quit(); setTimeout(() => process.exit(error ? 1 : 0), 250);
}
bot.on('error', finish);
bot.on('kicked', reason => finish(new Error(JSON.stringify(reason))));
async function complete(text) {
  const id = ++transaction;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { bot._client.off('tab_complete', listener); reject(new Error('No completion response: ' + text)); }, 8000);
    function listener(packet) {
      if (packet.transactionId !== id) return;
      clearTimeout(timer); bot._client.off('tab_complete', listener);
      const names = packet.matches.map(match => match.match);
      console.log('COMPLETE', JSON.stringify(text), names.slice(0, 12)); resolve(names);
    }
    bot._client.on('tab_complete', listener);
    bot._client.write('tab_complete', { transactionId: id, text });
  });
}
bot.once('spawn', () => (async () => {
  await delay(800); bot.chat('/serverpass 1234'); await delay(1600);
  bot.chat('/ppp use AlphaHero'); await delay(1200);
  for (const text of ['/tp Al', '/teleport Al', '/minecraft:tp Al', '/gamemode creative Al', '/msg Al']) {
    assert((await complete(text)).includes('AlphaHero'), 'Missing profile suggestion: ' + text); await delay(150);
  }
  assert((await complete('/tp Ppp')).includes(bot.username), 'Native player suggestion missing');
  assert((await complete('/gamemode c')).includes('creative'), 'Native game mode suggestion missing');
  assert((await complete('/give @s minecraft:sto')).some(name => name.includes('stone')), 'Native item suggestion missing');
  const commands = await complete('/ppp ');
  assert(commands.includes('language') && commands.includes('mode') && commands.includes('admin'), 'PPP options missing');
  const before = messages.length;
  bot.chat('/minecraft:tp AlphaHero ~ ~ ~'); await delay(500);
  assert(!messages.slice(before).some(text => /no player|not found|unknown|incorrect argument/i.test(text)), 'Profile target execution failed');
  assert(tree, 'No command tree received');
  const tp = tree.nodes[tree.rootIndex].children.map(index => tree.nodes[index])
    .find(node => node.extraNodeData?.name === 'tp');
  assert(tp, 'Native teleport root missing from operator tree');
  const entityArguments = [], visited = new Set();
  function walk(node) {
    if (!node || visited.has(node)) return; visited.add(node);
    if (node.extraNodeData?.parser === 'minecraft:entity') entityArguments.push(node);
    node.children.forEach(index => walk(tree.nodes[index]));
    if (node.redirectNode != null) walk(tree.nodes[node.redirectNode]);
  }
  walk(tp);
  assert(entityArguments.length > 0 && entityArguments.every(node => node.extraNodeData.suggestionType === 'minecraft:ask_server'),
    'The client must request server suggestions for native teleport targets');
  const report = process.env.PPP_TEST_REPORTS || 'build/reports/command-completion';
  fs.mkdirSync(report, { recursive: true });
  fs.writeFileSync(path.join(report, 'commands.json'), JSON.stringify(tree, null, 2));
  console.log('PPP_COMPLETIONS_OK: profile aliases, native players/items/modes, PPP options and native teleport execution');
  finish();
})().catch(finish));
setTimeout(() => finish(new Error('Completion test timed out')), 45000).unref();
