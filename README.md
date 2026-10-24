# NoteUp

An e-ink style AI notebook for Samsung Galaxy Tabs with an S Pen (built for the Tab S6 Lite and Tab S9 series), modeled on how the iFlytek AINOTE works — plus a few Claude twists.

## Install

1. On the tablet, open this repo's **Releases → latest** and tap **NoteUp.apk**.
2. Allow "Install unknown apps" for your browser when Android asks.
3. New builds install over the old one — your notes stay.

Every push to `main` rebuilds the APK automatically (Actions tab).

## What it does

**Like the AINOTE**
- Notebook shelf with paper templates: lined, grid, dots, Cornell, blank
- Pressure-sensitive S Pen ink; hold the pen's side button to erase; lasso to select
- Pen-only mode (palm rejection) — fingers swipe pages instead of drawing
- Meeting recording with live transcript (on-device on Android 13+, or your own Whisper server)
- **Ink ↔ audio sync**: strokes written while recording are linked to that moment. Turn on **Sync ▶** and tap any line of ink to hear what was being said
- Handwriting → text, fully on-device (ML Kit)
- Tasks list
- E-ink feel: black and white, square edges, serif type, no animations, optional refresh blink on page turn

**Claude twists**
- **Writes back in the margin** — lasso a question, equation or idea and Claude answers next to it on the page
- **Minutes** — recording + your handwritten notes → summary, decisions, action items; action items go straight to Tasks
- **Ask your notes** — one question across every notebook, transcript and set of minutes, with where each answer came from
- **Tidy with AI** — fixes handwriting-recognition mistakes by looking at the page image
- **Flashcards** from any converted page

## AI setup (Settings tab)

- **Claude**: paste an API key from console.anthropic.com.
- **Local server**: any OpenAI-compatible endpoint (Ollama at `http://<ip>:11434`, llama.cpp, LM Studio). Use a vision model if you want margin answers from local AI.
- Turn on **fall back** to use one when the other is unreachable.
- **Whisper server** (optional): any OpenAI-compatible `/v1/audio/transcriptions` server, e.g. faster-whisper-server.

Notes stay on the tablet. Page images or text are only sent to the AI you choose when you tap an AI action.

## Build locally

Open in Android Studio, or `./gradlew assembleRelease`. The signing key in `app/` is a throwaway key for sideloading only.
