# Opensolr Notes

A very simple notes app for Android, like a plain Google Keep, with every note kept in your own Opensolr Index.

- Sign in with your Opensolr account. The app creates one index for your notes (web crawler schema) and puts its hosted search page, `https://search.opensolr.com/<index>`, behind the index's own HTTP user and password.
- A note is text. The title is optional: without one, the first sentence is the title (at most 250 characters) and the first two sentences the description (at most 1200), as the index gets them.
- Every note is indexed with its creation date and, when you give one, a category.
- Search by words and by meaning (`{!hybrid}`), filter by date and by category.
- Notes sync to every phone signed in to the same account. Deleting a note deletes it from the index.
- Updates from GitHub releases; a copy installed from a store updates through that store.

## Build

```
./gradlew assembleGithubRelease bundlePlayRelease
```

A `signing.properties` next to `build.gradle.kts` signs the release build (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). It is never committed.
