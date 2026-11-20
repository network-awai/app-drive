import test from 'node:test';
import assert from 'node:assert/strict';
import {fixturePackInPrompt} from '../scripts/qa-pack-consent.mjs';

test('QA allows only a declared pack GET rendered by the canonical consent host',()=>{
  const id='a'.repeat(64);
  const prompt=['Service: awai.fs','Operation: download','Capability: awai.drive.sync',
    'Endpoint: https://yataverse.com','Action: GET /awai-drive/v1/packs/'+id,
    'Data categories: encrypted-drive-pack'].join('\n');
  const expected=new Set([id]);
  assert.equal(fixturePackInPrompt(prompt,expected),id);
  for(const variant of [
    prompt.replace('https://yataverse.com','https://other.example'),
    prompt.replace('Action: GET','Action: PUT'),
    prompt.replace('/'+id,'/'+id+'?unexpected=1'),
    prompt+'\nAction: GET /awai-drive/v1/packs/'+id,
    prompt.replace(id,'b'.repeat(64)),
  ])assert.equal(fixturePackInPrompt(variant,expected),undefined);
});
