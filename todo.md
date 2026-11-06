# Dream DisplaysX roadmap

- [ ] Smoke-test the opt-in animated video-loading placeholder on Fabric/NeoForge and Android: default-off UI, first-frame handoff, errors/retry, rotated flat/curved displays, resource reload, and shader replay.
- [ ] Repeat natural-EOF playback on ARM64 FCL/Pojav with the new JNI load-order mitigation and confirm no `libvlc.so+0xef7418` crash.
- [ ] Smoke-test the cyan selection outline on Fabric and NeoForge with `display.particles = true` and the disabled setting.
- [ ] If EOF crashes persist, add temporary native instrumentation for JNA/VLC pthread-key creation and destructor order.
- [ ] Smoke-test the uploader profile page: fuzzy `@` results, 30-video pages, zone/collection tabs, page-number input, back navigation, and clearing the search box.
- [ ] Verify local and synced displays resume from their saved position after leaving and re-entering a world.
