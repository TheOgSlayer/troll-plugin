# TrollPlugin

OP-only troll commands for Paper 1.21.4+.

| Command | Effect |
|---|---|
| `/troll jumpscare <player>` | Full-screen scary images + loud screams |
| `/troll keyboard <player>` | Blocks movement/hotbar/chat/etc. for 60s |
| `/troll nonstopjump <player>` | Forces constant jumping for 60s |
| `/troll loadingscreen <player>` | Full-screen Mojang-style loading screen |

## Setup
1. Push this repo to GitHub (public). Actions builds the jar automatically.
2. Tag a release (`git tag v1.0 && git push --tags`) and download `TrollPlugin-1.0.jar` from Releases.
3. Put the jar in your server's `plugins/` folder and start the server once.
4. Edit `plugins/TrollPlugin/config.yml`:
   ```yaml
   resource-pack:
     url: "https://raw.githubusercontent.com/<you>/troll-plugin/main/TrollPack.zip"
     sha1: "68bf4c1623b2e02ef223f84e88b7b34fd85ec317"
     force: true
   ```
5. Restart. Players accept the pack when they join; then `/troll ...` works. Players without the pack get vanilla fallback effects.

If you change `resource-pack/`, run `cd resource-pack && zip -r ../TrollPack.zip .`, commit it, and update the sha1 (`sha1sum TrollPack.zip`).
