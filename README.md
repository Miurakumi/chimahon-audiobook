<div align="center">

<img width="200" height="200" src="./app/src/main/res/drawable/chimahon.png" alt="Chimahon icon" />
<h1 align="center">Chimahon Dial</h1>

**A fork of [Chimahon](https://github.com/sohilsayed/chimahon) that keeps everything the original has, plus an audiobook player in my own style.**

</div>

---

## What this is

Chimahon Dial is a personal fork of Chimahon, the Android immersion reader for manga, novels and anime (built on Mihon/Komikku). Everything in the original is still here and works the same way. The only difference is the audiobook player described below.

For the full list of features of the original app, see the [original Chimahon README](https://github.com/sohilsayed/chimahon#readme).

## What I added: the audiobook dial

Inside the EPUB reader there is a headphones button that opens the audiobook dial.

- **Audio + SRT**: pick an audio file and its subtitles (SRT). The lines show in a strip; you move the dial and the audio follows the active line.
- **Tap to listen or look up**: tap a line to jump to it and play it, or tap a word of the active line to open the dictionary.
- **Play modes**: all lines, quotes only (lines that start with 「), or manual. Speed from 0.75x to 1.5x.
- **Remembers your place**: last line, mode and speed are saved for each book.
- **Your reader settings**: text size, line spacing, character spacing and font are taken from the reader.
- **Anki**: `{sentence-audio}` gets the audio clip of the active line, and `{cover}` adds the book cover.
- **Immersion mode**: background music by scene mood (Fight, Day to day, Sadness, Hope, Comedy). You choose one folder of your own music for each mood.

## Download

Builds are in the 1.0.

*Requires Android 8.0 or higher.*

## Credits

This project is built on the work of the original Chimahon, Mihon and Komikku teams. Thanks to everyone who made them.

- [Chimahon](https://github.com/sohilsayed/chimahon) (original project)
- [Mihon](https://github.com/mihonapp/mihon) and [Komikku](https://github.com/komikku-app/komikku)
- [Yomitan](https://github.com/yomidevs/yomitan), [owocr](https://github.com/AuroraWright/owocr), [hoshidicts](https://github.com/Manhhao/hoshidicts/) and [manga-panel-detector-yolo26n](https://huggingface.co/leoxs22/manga-panel-detector-yolo26n), used by the original app

## License

Licensed under the **GNU General Public License v3.0**, the same as the original. See [LICENSE](./LICENSE).
