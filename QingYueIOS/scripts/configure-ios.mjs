import {readFile,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const root=fileURLToPath(new URL('../',import.meta.url));
const read=rel=>readFile(path.join(root,rel),'utf8');
const write=(rel,data)=>writeFile(path.join(root,rel),data);
let project=await read('ios/App/App.xcodeproj/project.pbxproj');
project=project.replace(/IPHONEOS_DEPLOYMENT_TARGET = [\d.]+;/g,'IPHONEOS_DEPLOYMENT_TARGET = 18.0;')
  .replace(/MARKETING_VERSION = [\d.]+;/g,'MARKETING_VERSION = 0.1.0;');
if(!project.includes('515950524956414359000001')) {
  project=project.replace('/* End PBXBuildFile section */','\t\t515950524956414359000002 /* PrivacyInfo.xcprivacy in Resources */ = {isa = PBXBuildFile; fileRef = 515950524956414359000001 /* PrivacyInfo.xcprivacy */; };\n/* End PBXBuildFile section */');
  project=project.replace('/* End PBXFileReference section */','\t\t515950524956414359000001 /* PrivacyInfo.xcprivacy */ = {isa = PBXFileReference; lastKnownFileType = text.xml; path = PrivacyInfo.xcprivacy; sourceTree = "<group>"; };\n/* End PBXFileReference section */');
  project=project.replace('504EC3131FED79650016851F /* Info.plist */,','504EC3131FED79650016851F /* Info.plist */,\n\t\t\t\t515950524956414359000001 /* PrivacyInfo.xcprivacy */,');
  project=project.replace('504EC30F1FED79650016851F /* Assets.xcassets in Resources */,','504EC30F1FED79650016851F /* Assets.xcassets in Resources */,\n\t\t\t\t515950524956414359000002 /* PrivacyInfo.xcprivacy in Resources */,');
}
await write('ios/App/App.xcodeproj/project.pbxproj',project);
let plist=await read('ios/App/App/Info.plist');
plist=plist.replace('<string>en</string>','<string>zh_CN</string>');
for(const key of ['UIFileSharingEnabled','LSSupportsOpeningDocumentsInPlace']) {
  if(!plist.includes(`<key>${key}</key>`)) {
    const last=plist.lastIndexOf('</dict>');
    plist=plist.slice(0,last)+`\t<key>${key}</key>\n\t<true/>\n`+plist.slice(last);
  }
}
// The appearance panel supports system/light/dark; allow WKWebView to observe the system.
plist=plist.replace(/\s*<key>UIUserInterfaceStyle<\/key>\s*<string>Light<\/string>/g,'');
await write('ios/App/App/Info.plist',plist);
let spm=await read('ios/App/CapApp-SPM/Package.swift');
spm=spm.replace('.iOS(.v15)','.iOS(.v18)')
  .replace(/path: "[^"\n]*node_modules\/@capacitor\/filesystem"/,'path: "../../../node_modules/@capacitor/filesystem"')
  .replace(/path: "[^"\n]*node_modules\/@capacitor\/share"/,'path: "../../../node_modules/@capacitor/share"');
if(!spm.includes('ion-ios-filesystem.git'))spm=spm.replace('dependencies: [','dependencies: [\n        .package(url: "https://github.com/ionic-team/ion-ios-filesystem.git", exact: "1.1.1"),');
await write('ios/App/CapApp-SPM/Package.swift',spm);
console.log('iPhone/iPad、iOS 18、文件共享和隐私清单配置已核对。');
