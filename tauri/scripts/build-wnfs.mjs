import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

if (process.env.AWAI_DRIVE_WNFS_SYNC === '1') {
  const cwd = fileURLToPath(new URL('../', import.meta.url));
  execFileSync(process.execPath, ['scripts/stage-wnfs.mjs'], { cwd, stdio: 'inherit' });
  execFileSync('clojure', ['-M:wnfs'], { cwd, stdio: 'inherit' });
  await build({
    absWorkingDir: cwd,
    entryPoints: ['.build/drive-wnfs-cljs-bundle/core.cjs'],
    outfile: 'generated/wnfs-core.js',
    bundle: true, platform: 'browser', format: 'iife', target: 'es2020', minify: true,
    inject: ['scripts/wnfs-browser-buffer.mjs']
  });
}
