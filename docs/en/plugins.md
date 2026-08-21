# HyperLyric Plugins

Plugins are optional HyperLyric extensions delivered as ZIP packages and installed on demand. Each plugin provides a relatively independent set of lyric-processing capabilities, while the main App handles installation, configuration, and runtime management.

## Capabilities

Plugins can add translation, romanization, word-level information, lyric cleanup, or other content enhancements to the lyric pipeline. The result depends on the plugin implementation and on the data provided by the current lyric source.

Plugin processing runs in the background, so the original lyrics are shown first. If a plugin has no result, a request fails, processing times out, or the result does not pass validation, HyperLyric keeps the current lyrics and basic playback and lyric display continue normally.

## Configuration and activation

Plugins are installed, configured, and enabled from the plugin manager. Configuration changes normally take effect from the next song; the current song is not rerun automatically. Restart the system interface (SystemUI) after installing, removing, or updating plugin code. Ordinary configuration changes do not require a restart.

## Current plugins

- **OpenAI Lyric Translation**: generates lyric translations through an OpenAI-compatible API, with options such as target language and model.
- **Demo Lyric Plugin**: used for development and testing; it normally does not need to be installed.

For information about building a new lyric-processing plugin, see the [plugin development documentation](plugin-development.md).

