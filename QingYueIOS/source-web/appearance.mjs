// UI preferences are device-local and never change documents or backup formats.
export const APPEARANCE_KEY='qingyue-appearance';
export function normalizeAppearance(value){return {style:value?.style==='classic'?'classic':'warm',mode:['system','light','dark'].includes(value?.mode)?value.mode:'system'};}
export function readAppearance(storage){try{return normalizeAppearance(JSON.parse((storage??globalThis.localStorage).getItem(APPEARANCE_KEY)));}catch{return normalizeAppearance();}}
export function resolveTheme(mode,systemDark){return mode==='dark'||mode==='system'&&systemDark?'dark':'light';}
export function applyAppearance(value=readAppearance()){
 const preferences=normalizeAppearance(value),root=document.documentElement;
 root.dataset.ui=preferences.style;root.dataset.theme=resolveTheme(preferences.mode,matchMedia('(prefers-color-scheme: dark)').matches);
 document.querySelector('meta[name="theme-color"]')?.setAttribute('content',root.dataset.theme==='dark'?'#252321':preferences.style==='classic'?'#fff7ed':'#fff9f1');
 return preferences;
}
export function saveAppearance(value){const preferences=applyAppearance(value);try{localStorage.setItem(APPEARANCE_KEY,JSON.stringify(preferences));return true;}catch{return false;}}
