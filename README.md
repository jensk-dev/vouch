# Vouch

Vouch is a server side Fabric mod for Minecraft 26.2 that lets your community manage the
whitelist. Trusted players invite their friends directly. Regular members vouch for
newcomers, and a trusted player approves or denies the vouch. Every action is written to
an audit log.

## Commands

| Command                   | What it does                                        | Permission      |
|---------------------------|-----------------------------------------------------|-----------------|
| `/invite <players>`       | Adds the players to the whitelist right away.       | `vouch.invite`  |
| `/vouch for <player>`     | Creates a pending vouch.                            | `vouch.vouch`   |
| `/vouch approve <player>` | Approves a pending vouch and whitelists the player. | `vouch.approve` |
| `/vouch deny <player>`    | Rejects a pending vouch.                            | `vouch.approve` |
| `/vouch list`             | Shows pending vouches with clickable buttons.       | `vouch.approve` |
| `/vouch history [player]` | Shows the recent audit log.                         | `vouch.audit`   |

The mod needs Fabric API. LuckPerms is recommended for granting the permission nodes.

Licensed under MIT.
