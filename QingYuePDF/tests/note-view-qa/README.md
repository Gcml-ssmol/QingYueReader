# note-view-qa

Runs production NoteCanvasView touch dispatch/drawing and NoteActionMenu show/click logic with recording Android dependency shims.

Run `./run.ps1 -BuildDirectory <output>` in the integrated project. For independent delivery validation, `-ProductionDirectory <directory containing merged production Java files>` selects a read-only test input snapshot.

The shims are not Android rendering, WindowManager placement, disk storage, async image decoding, or font-layout verification. See delivery/1.14.0-editor-reference-qa.md for exact coverage and device limitations. No production gesture or page-layout logic is mirrored in these tests.

