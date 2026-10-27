# Opensolr Notes

A very simple notes app for Android, like a plain Google Keep, with every note kept in your own Opensolr Index.

- Sign in with your Opensolr account. The app creates one index for your notes (web crawler schema). Its hosted search page, `https://search.opensolr.com/<index>` (and `https://notes.opensolr.com`), opens with the account's address and its two-factor code, sent by SMS when the account keeps a phone number for it. The index's own HTTP user and password can be turned on as an extra step from the index's security settings.
- A note is text with bold, italic, underline and links (and, from the web editor, what a pasted web page brings: headings, lists, tables). The title is optional: without one, the first sentence is the title (at most 250 characters) and the first two sentences the description (at most 1200), as the index gets them.
- Every note is indexed with its creation date.
- Search by words and by meaning (`{!hybrid}`), filter by date.
- Notes sync to every phone that shares the index, in one call: a refresh with nothing new is almost free.
- Deleting a note deletes it from the index and from the other phones; a deleted note stays deleted even when another phone still had an unsent edit of it.
- The order you drag notes into reaches every phone and the hosted search page; the last device to order them wins.
- Long press a note to pick it, keep the finger down and drag to pick every note in between (the list scrolls at the edges), then delete the picked notes at once after a confirmation. Works in search results too.
- On the hosted search page, every note has a checkbox and the picked notes are deleted together (owner only); the list with no query follows the order set in the app.
- The app keeps the notes index on the current configuration by itself, keeping the notes safe while it changes.
- Updates from GitHub releases; a copy installed from a store updates through that store.

## Build

```
./gradlew assembleGithubRelease bundlePlayRelease
```

A `signing.properties` next to `build.gradle.kts` signs the release build (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). It is never committed.
