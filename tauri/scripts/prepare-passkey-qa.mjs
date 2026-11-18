import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdir, readFile, unlink, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const packageName = 'network.awai.drive';
const qaPackage = packageName + '.passkey3099';
const repo = fileURLToPath(new URL('../../', import.meta.url));
assert(process.argv.length === 3, 'Supply a new QA output directory');
const output = resolve(process.argv[2]);
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
await writeFile(configPath, JSON.stringify(config, null, 2) + '\n');

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
const qaJava = join(java, 'passkey3099');
let activity = substitute(await readFile(original, 'utf8'), 'package ' + packageName, 'package ' + qaPackage);
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
console.log(output);

async function replace(file, before, after) {
  await writeFile(file, substitute(await readFile(file, 'utf8'), before, after));
}

function substitute(source, before, after) {
  assert.equal(source.split(before).length, 2, 'Expected exactly one ' + before);
  return source.replace(before, after);
}
