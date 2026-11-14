# Create a new release

1. Update `mod_version` in `gradle.properties`.
2. Commit that change.
3. Push that commit.
4. Create a matching annotated git tag in the form `vX.Y.Z` so the tag message becomes the GitHub release notes. Run
   one of these two commands:
   - for a short release note, `git tag -a vX.Y.Z -m "Summarise the release here"`;
   - for longer release notes, put them in a file and run `git tag -a vX.Y.Z -F RELEASE_NOTES.md`.
5. Push the tag with `git push origin vX.Y.Z`.

The release workflow first runs `build.yml` on the tag with a read-only token: the strict build (`-PstrictQuality=true`) and the client GameTests. It publishes nothing unless both pass.
The publishing job then takes the jar that build made (it runs no build code of its own, so its write token never meets the build's plugins and dependencies), reads the annotated tag message and uses it as the GitHub release body.
If the tag has no annotation text, GitHub auto-generated release notes are used as a fallback.
