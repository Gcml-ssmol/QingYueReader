import {Capacitor} from '@capacitor/core';
import {Filesystem, Directory} from '@capacitor/filesystem';
import {Share} from '@capacitor/share';
import {createExporter} from './export-file.mjs';

// No network endpoint: exports stay in this app's Documents directory.
const exporter = createExporter({filesystem: Filesystem, share: Share, directory: Directory.Documents});
window.QYNative = Object.freeze({
  isNative: Capacitor.isNativePlatform(),
  saveBlob: exporter.saveBlob,
  shareFile: exporter.shareFile,
});
