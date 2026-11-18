import {build} from 'esbuild';import{readFile}from'node:fs/promises';import{createHash}from'node:crypto';import{fileURLToPath}from'node:url';
const app=fileURLToPath(new URL('../',import.meta.url)),pins=JSON.parse(await readFile(app+'vendor/PINS.json'));
for(const f of [...pins.canonical.files,{...pins.car,artifactSha256:pins.car.sha256}]){const b=await readFile(app+'vendor/'+f.target);if(createHash('sha256').update(b).digest('hex')!==f.artifactSha256)throw Error('vendor pin drift');}
// Storage receipt verifier (receipt/verify.mjs, AWAI-3314): pinned @noble packages, browser ESM.
await build({entryPoints:[app+'receipt/verify.mjs'],outfile:app+'generated/receipt.js',bundle:true,platform:'browser',format:'esm',target:'es2020',minify:true,absWorkingDir:app,nodePaths:[app+'node_modules']});
const { modules } = await import('../wnfs/prepare.mjs');
const paths = new Set(modules.values());
const wnfsPorts = {
  name: 'wnfs-browser-host',
  setup(build) {
    build.onResolve({ filter: /.*/ }, ({ path }) =>
      paths.has(path) ? { path: app + 'node_modules/' + path } : undefined);
  }
};
await build({entryPoints:[app+'.build/drive-cljs-bundle/core.cjs'],outfile:app+'generated/core.js',bundle:true,plugins:[wnfsPorts],inject:[app+'wnfs/buffer.mjs'],platform:'browser',format:'iife',target:'es2020',minify:true,absWorkingDir:app,nodePaths:[app+'node_modules']});
