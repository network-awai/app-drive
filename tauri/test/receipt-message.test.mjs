import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {messages, translator} from '../src/i18n.js';
import {verifyDrivePackReceipt} from '../generated/receipt.js';

const read=(p)=>JSON.parse(fs.readFileSync(new URL(p,import.meta.url),'utf8'));
const REAL=read('./fixtures/yataverse-pack-receipt-20261118.json');
const META=read('./fixtures/yataverse-pack-receipt-20261118.meta.json');
const TRUST=read('../receipt-trust.json')['https://yataverse.com'];
const text=(p)=>fs.readFileSync(new URL(p,import.meta.url),'utf8');
const grab=(path,name,args)=>{
  const hit=text(path).match(new RegExp(`function ${name}\\(${args}\\) \\{[\\s\\S]*?\\n\\}`));
  assert.ok(hit,`could not extract ${name} from ${path}`);
  return hit[0];
};
const verifySource=grab('../src/sync.js','verifyReceipt','receipt, expected');
const failSource=grab('../src/app.js','fail','e');
const verifyReceipt=new Function('verifyDrivePackReceipt','receiptKeys',`return ${verifySource}`)(verifyDrivePackReceipt,TRUST);
const expected={id:META.id,size:META.size};
const clone=()=>structuredClone(REAL);
const absent={sha256:META.id,bytes:META.size};
const nullReceipt={...clone(),receipt:null};
const badSignature=clone();badSignature.receipt.receipt.acceptedAt+=1;
const missingCases=[['absent receipt',absent],['null receipt',nullReceipt]];
const invalidCases=[
  ['bad signature',badSignature,expected,'bad-signature'],
  ['wrong pack',REAL,{id:'cd'.repeat(32),size:META.size},'subject-mismatch'],
];
const caught=(fn,want,label)=>{
  let error;try{fn();}catch(e){error=e;}
  assert.equal(error?.message,want,`${label}: verifyReceipt should throw exactly ${want}`);
  return error;
};

test('missing receipts get their own safe message in every language',()=>{
  assert.doesNotThrow(()=>verifyReceipt(REAL,expected),'a valid receipt must verify');
  const missingErrors=missingCases.map(([label,receipt])=>caught(()=>verifyReceipt(receipt,expected),'storage-receipt-invalid:receipt-missing',label));
  const invalidErrors=invalidCases.map(([label,receipt,want,code])=>caught(()=>verifyReceipt(receipt,want),`storage-receipt-invalid:receipt-${code}`,label));
  for(const lang of Object.keys(messages)){
    const {t}=translator(lang);
    let shown;
    const nodes={edit:{open:true},'form-error':{textContent:''}};
    const $=id=>nodes[id]||(nodes[id]={textContent:''});
    const message=s=>{shown=s;};
    const fail=new Function('messages','lang','t','message','$','return '+failSource)(messages,lang,t,message,$);
    const show=e=>{
      shown=undefined;nodes['form-error'].textContent='';fail(e);
      assert.equal(nodes['form-error'].textContent,shown,`${lang}: form-error should receive the sentence while edit is open`);
      return shown;
    };
    const missingShown=missingErrors.map(show),invalidShown=invalidErrors.map(show);
    assert.equal(new Set(missingShown).size,1,`${lang}: missing receipt cases should share one sentence`);
    assert.equal(new Set(invalidShown).size,1,`${lang}: invalid receipt cases should share one sentence`);
    const missingText=missingShown[0],invalidText=invalidShown[0];
    assert.notEqual(missingText,invalidText,`${lang}: missing and invalid receipts need different sentences`);
    assert.equal(invalidText,messages[lang]['error.storage-receipt-invalid'],`${lang}: invalid receipts keep the existing message`);
    const tail=lang==='ja'?'同期済みにしていません。元のファイルを保持しています。':'Nothing was marked as synced; original files have been kept.';
    const rest=s=>s.slice(s.indexOf(lang==='ja'?'。':'.')+1).trimStart();
    assert.equal(rest(missingText),tail,`${lang}: missing receipt message must keep the preservation promise`);
    assert.equal(rest(invalidText),tail,`${lang}: invalid receipt message must keep the preservation promise`);
    for(const [kind,sentence] of [['missing',missingText],['invalid',invalidText]])
      assert.doesNotMatch(sentence,/storage-receipt|receipt-[a-z]|:receipt/,`${lang}: ${kind} message leaks an internal code`);
  }
});
