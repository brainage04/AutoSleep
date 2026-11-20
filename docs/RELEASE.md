# Create a new release

1. Update `mod_version` in `gradle.properties`.
2. Commit that change to Git.
3. Push that commit to GitHub.
4. Create a matching annotated git tag in the form `vX.Y.Z` so the tag message becomes the GitHub release notes.

    - For a short release note, run `git tag -a v1.0.1 -m "Summarise the release here"`.

    - For longer release notes, put them in a file and run `git tag -a v1.0.1 --cleanup=verbatim -F RELEASE_NOTES.md`. Without `--cleanup=verbatim`, git deletes every line that starts with `#`, so Markdown headings would vanish from the notes.

1. Push the tag with `git push origin v1.0.1`.

The release workflow first runs `build.yml` on the tag with a read-only token: the strict build (`-PstrictQuality=true`) and the client GameTests. It publishes nothing unless both pass.
The publishing job then takes the jar that build made (it runs no build code of its own, so its write token never meets the build's plugins and dependencies), reads the annotated tag message and uses it as the GitHub release body.
If the tag has no annotation text, GitHub auto-generated release notes are used as a fallback.
