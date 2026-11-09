import test from 'node:test';
import assert from 'node:assert/strict';
import {normalizeAppearance,readAppearance,resolveTheme} from '../dist/appearance.mjs';
test('missing, malformed and future preferences resolve to a safe new default',()=>{
 assert.deepEqual(normalizeAppearance(),{style:'warm',mode:'system'});
 assert.deepEqual(readAppearance({getItem:()=>'{broken'}),{style:'warm',mode:'system'});
 assert.deepEqual(readAppearance({getItem:()=>'{"style":"future","mode":"sepia"}'}),{style:'warm',mode:'system'});
 assert.deepEqual(readAppearance({getItem:()=>{throw new Error('blocked');}}),{style:'warm',mode:'system'});
});
test('classic layout and explicit theme survive preference parsing',()=>{
 assert.deepEqual(readAppearance({getItem:()=>'{"style":"classic","mode":"dark"}'}),{style:'classic',mode:'dark'});
 assert.equal(resolveTheme('light',true),'light');assert.equal(resolveTheme('dark',false),'dark');
 assert.equal(resolveTheme('system',true),'dark');assert.equal(resolveTheme('system',false),'light');
});
test('a browser blocking the localStorage getter does not prevent startup',()=>{
 const descriptor=Object.getOwnPropertyDescriptor(globalThis,'localStorage');
 try{
  Object.defineProperty(globalThis,'localStorage',{configurable:true,get(){throw new Error('Storage access denied');}});
  assert.deepEqual(readAppearance(),{style:'warm',mode:'system'});
 }finally{if(descriptor)Object.defineProperty(globalThis,'localStorage',descriptor);else delete globalThis.localStorage;}
});
