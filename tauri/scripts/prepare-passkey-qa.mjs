import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdir, readFile, unlink, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const packageName = 'network.awai.drive';
const args = process.argv.slice(2);
const macOS = args[0] === '--macos';
const androidBroker = args[0] === '--android-broker';
const qaPackage = packageName + (macOS ? '.passkeyqa.macos' : androidBroker ? '.passkeyqa.android' : '.passkey3099');
const homePackage = 'network.awai.home';
const qaHome = homePackage + '.passkeyqa';
const homeRevision = '5ff24113ace346912629618e0e8b6b068426f77e';
const repo = fileURLToPath(new URL('../../', import.meta.url));
assert(args.length === (androidBroker ? 3 : macOS ? 2 : 1) && (androidBroker || macOS || !args[0].startsWith('-')),
  'Usage: prepare-passkey-qa.mjs [--macos | --android-broker AWAI_OS_REPOSITORY] NEW_OUTPUT_DIRECTORY');
const output = resolve(args.at(-1));
await mkdir(output);
const archive = execFileSync('git', ['archive', 'HEAD', 'tauri'], {
  cwd: repo,
  maxBuffer: 100 * 1024 * 1024,
});
execFileSync('tar', ['-xf', '-', '-C', output], { input: archive });

const tauri = join(output, 'tauri', 'src-tauri');
const configPath = join(tauri, 'tauri.conf.json');
const config = JSON.parse(await readFile(configPath, 'utf8'));
assert.equal(config.identifier, packageName);
config.identifier = qaPackage;
config.productName = 'Awai Drive Passkey QA';
for (const window of config.app.windows) window.title = config.productName;
await writeFile(configPath, JSON.stringify(config, null, 2) + '\n');

if (!macOS) await prepareAndroid();
if (androidBroker) await prepareHome(resolve(args[1]));
console.log(output);

async function prepareAndroid() {
  const app = join(tauri, 'gen', 'android', 'app');
  const main = join(app, 'src', 'main');
  await replace(join(app, 'build.gradle.kts'), 'namespace = "' + packageName + '"',
    'namespace = "' + qaPackage + '"');
  await replace(join(app, 'build.gradle.kts'), 'applicationId = "' + packageName + '"',
    'applicationId = "' + qaPackage + '"');
  await replace(join(main, 'AndroidManifest.xml'), 'android:name=".DriveDocuments"',
    'android:name="' + packageName + '.DriveDocuments"');
  const strings = join(main, 'res', 'values', 'strings.xml');
  await writeFile(strings, (await readFile(strings, 'utf8')).replaceAll('>Awai Drive<', '>Awai Drive Passkey QA<'));

  const java = join(main, 'java', ...packageName.split('.'));
  const original = join(java, 'MainActivity.kt');
  const qaJava = join(java, ...qaPackage.slice(packageName.length + 1).split('.'));
  let activity = substitute(await readFile(original, 'utf8'), 'package ' + packageName, 'package ' + qaPackage);
  if (androidBroker) {
    const manifest = join(main, 'AndroidManifest.xml');
    await replace(manifest, 'android:name="' + homePackage + '"', 'android:name="' + qaHome + '"');
    const plugin = join(java, 'DrivePlugin.kt');
    await replace(plugin, 'val home = "' + homePackage + '"', 'val home = "' + qaHome + '"');
    await replace(plugin, 'Intent("' + homePackage + '.WNFS_ACCOUNT_PROOF")',
      'Intent("' + qaHome + '.WNFS_ACCOUNT_PROOF")');
    for (const suffix of ['fileprovider', 'documents']) {
      const before = packageName + '.' + suffix;
      const after = qaPackage + '.' + suffix;
      await writeFile(plugin, (await readFile(plugin, 'utf8')).replaceAll(before, after));
    }
    await mkdir(qaJava, {recursive: true});
    await writeFile(join(qaJava, 'MainActivity.kt'), activity);
    await unlink(original);
    return;
  }
  activity = substitute(activity, 'import kotlin.math.max', [
    'import kotlin.math.max',
    'import androidx.core.content.ContextCompat',
    'import androidx.webkit.ProxyConfig',
    'import androidx.webkit.ProxyController',
    'import androidx.webkit.WebViewFeature',
  ].join('\n'));
  activity = substitute(activity, '  super.onCreate(savedInstanceState)', [
    '  if (intent.getBooleanExtra("qaRelayProxy", false)) {',
    '   check(packageName == "' + qaPackage + '")',
    '   check(WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE))',
    '   val config = ProxyConfig.Builder()',
    '    .addProxyRule("http://127.0.0.1:19430", ProxyConfig.MATCH_HTTPS)',
    '    .addDirect(ProxyConfig.MATCH_ALL_SCHEMES)',
    '    .build()',
    '   ProxyController.getInstance().setProxyOverride(config, ContextCompat.getMainExecutor(this)) {}',
    '  }',
    '  super.onCreate(savedInstanceState)',
  ].join('\n'));
  await mkdir(qaJava);
  await writeFile(join(qaJava, 'MainActivity.kt'), activity);
  await unlink(original);
}

async function prepareHome(homeRepo) {
  const home = join(output, 'awai-os');
  await mkdir(home);
  const source = execFileSync('git', ['archive', homeRevision, 'android', 'otehon'], {
    cwd: homeRepo, maxBuffer: 200 * 1024 * 1024,
  });
  execFileSync('tar', ['-xf', '-', '-C', home], {input: source});
  const builder = join(home, 'android', 'build.py');
  await replace(builder, "if args.home_only:\n        home=build('home','" + homePackage + "',",
    "if args.home_only:\n        home=build('home','" + qaHome + "',");
  await replace(builder, `home = '<intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.HOME"/><category android:name="android.intent.category.DEFAULT"/></intent-filter>'`,
    "home = ''");
  await replace(builder, '<package android:name="' + packageName + '"/>', '<package android:name="' + qaPackage + '"/>');
  await replace(builder, "'home':'Awai Home'", "'home':'Awai Home Passkey QA'");
  const broker = join(home, 'android', 'home', 'src', 'network', 'awai', 'home', 'AccountBrokerActivity.java');
  await replace(broker, '"' + homePackage + '.WNFS_ACCOUNT_PROOF"', '"' + qaHome + '.WNFS_ACCOUNT_PROOF"');
  await replace(broker, '!("' + packageName + '".equals(app) || "network.awai.yataverse".equals(app))',
    '!"' + qaPackage + '".equals(app)');
  // The signed action identifies the protocol app, while Android authenticates the QA caller above.
  await replace(broker, '.put("app", app)', '.put("app", "' + packageName + '")');
  await replace(broker, '"' + packageName + '".equals(app) ? "Drive" : "Itonami"', '"Drive Passkey QA"');
}

async function replace(file, before, after) {
  await writeFile(file, substitute(await readFile(file, 'utf8'), before, after));
}

function substitute(source, before, after) {
  assert.equal(source.split(before).length, 2, 'Expected exactly one ' + before);
  return source.replace(before, after);
}
