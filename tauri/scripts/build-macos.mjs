// Build, sign and (optionally) notarize the macOS distribution of Awai Drive.
// Signing values come from the environment only; see docs/macos-signing.md.
//
//   APPLE_SIGNING_IDENTITY  Developer ID Application identity (name or SHA-1) in the
//                           login keychain. Required unless AWAI_DRIVE_UNSIGNED=1.
//   AWAI_NOTARY_PROFILE     notarytool keychain profile (`xcrun notarytool store-credentials`).
//                           When unset the build is signed but not notarized.
//   AWAI_DRIVE_SKIP_WEB_BUILD=1  reuse an existing dist/ instead of running `npm run build`.
//   AWAI_DRIVE_UNSIGNED=1   ad-hoc local bundle for layout checks only; never distribute it.
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {readFileSync, readdirSync, existsSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {resolve} from 'node:path';

if (process.platform !== 'darwin') throw Error('macOS distribution builds run on macOS');
const app = fileURLToPath(new URL('../', import.meta.url));
const config = JSON.parse(readFileSync(resolve(app, 'src-tauri/tauri.conf.json'), 'utf8'));
const env = {...process.env};
const identity = env.APPLE_SIGNING_IDENTITY || '';
const unsigned = env.AWAI_DRIVE_UNSIGNED === '1';
const profile = env.AWAI_NOTARY_PROFILE || '';
if (!identity && !unsigned) throw Error('set APPLE_SIGNING_IDENTITY to a Developer ID Application identity');
if (unsigned && profile) throw Error('an unsigned bundle cannot be notarized');
// Notarization is driven only by AWAI_NOTARY_PROFILE below; keep Tauri's own notarization off
// so that credentials in the shell never take a second, unreviewed path.
for (const k of ['APPLE_ID', 'APPLE_PASSWORD', 'APPLE_TEAM_ID', 'APPLE_API_KEY', 'APPLE_API_ISSUER', 'APPLE_API_KEY_PATH']) delete env[k];
if (unsigned) env.APPLE_SIGNING_IDENTITY = '-';

const run = (cmd, args, opts = {}) => {
  console.log('+', cmd, ...args);
  return execFileSync(cmd, args, {cwd: app, env, stdio: opts.capture ? ['ignore', 'pipe', 'pipe'] : 'inherit', encoding: 'utf8'});
};
const check = (cmd, args) => {
  try { return {ok: true, out: execFileSync(cmd, args, {env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe']})}; }
  catch (e) { return {ok: false, out: String(e.stdout || '') + String(e.stderr || '')}; }
};
const sha256 = f => createHash('sha256').update(readFileSync(f)).digest('hex');

if (identity && !unsigned) {
  const ids = run('security', ['find-identity', '-v', '-p', 'codesigning'], {capture: true});
  if (!ids.includes(identity)) throw Error('APPLE_SIGNING_IDENTITY is not a valid codesigning identity in the keychain search list');
  if (!/Developer ID Application/.test(ids.split('\n').find(l => l.includes(identity)) || ''))
    throw Error('APPLE_SIGNING_IDENTITY must be a Developer ID Application identity');
}
if (env.AWAI_DRIVE_SKIP_WEB_BUILD !== '1') run('npm', ['run', 'build']);
run('npx', ['tauri', 'build', '--bundles', 'app,dmg']);

const bundleDir = resolve(app, 'src-tauri/target/release/bundle');
const appPath = resolve(bundleDir, 'macos', `${config.productName}.app`);
const dmgName = readdirSync(resolve(bundleDir, 'dmg')).find(n => n.startsWith(`${config.productName}_${config.version}_`) && n.endsWith('.dmg'));
if (!existsSync(appPath) || !dmgName) throw Error('bundle output missing');
const dmgPath = resolve(bundleDir, 'dmg', dmgName);

if (!unsigned) {
  // Tauri signs the .app; sign the disk image too so Gatekeeper can assess it before mounting.
  if (!check('codesign', ['--verify', '--strict', dmgPath]).ok)
    run('codesign', ['--force', '--timestamp', '--sign', identity, dmgPath]);
  run('codesign', ['--verify', '--deep', '--strict', '--verbose=2', appPath]);
  run('codesign', ['--verify', '--strict', '--verbose=2', dmgPath]);
}
let notarized = false;
if (profile) {
  run('xcrun', ['notarytool', 'submit', dmgPath, '--keychain-profile', profile, '--wait']);
  run('xcrun', ['stapler', 'staple', dmgPath]);
  run('xcrun', ['stapler', 'staple', appPath]);
  run('xcrun', ['stapler', 'validate', dmgPath]);
  run('xcrun', ['stapler', 'validate', appPath]);
  notarized = true;
}
const gatekeeper = check('spctl', ['-a', '-vv', '-t', 'exec', appPath]);
const summary = {
  app: appPath,
  dmg: dmgPath,
  dmgSha256: sha256(dmgPath),
  executableSha256: sha256(resolve(appPath, 'Contents/MacOS', readdirSync(resolve(appPath, 'Contents/MacOS'))[0])),
  signed: !unsigned,
  notarized,
  gatekeeper: gatekeeper.out.trim(),
};
console.log(JSON.stringify(summary, null, 2));
if (!notarized) console.log('Not notarized: set AWAI_NOTARY_PROFILE to distribute outside this Mac (docs/macos-signing.md).');
