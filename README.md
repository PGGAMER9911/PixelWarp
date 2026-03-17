# PixelWarp

Advanced Warp System for Paper SMP servers with MySQL, GUI browsing, preview mode, access control, and safe teleports.

## At a Glance

- Plugin Name: PixelWarp
- Minecraft Version: Paper 1.21.1+
- Java Version: 21+
- Database: MySQL 5.7+ / 8.0+
- Type: Advanced Warp Management Plugin

## Why PixelWarp

PixelWarp is built for SMP servers that want fast, secure, and organized warp management.
It combines an easy player experience with admin-level control and analytics.

## Core Features

### Warp Management
- Create warps with custom name and category
- Public and private warp visibility
- Rename and edit warp metadata
- Secure delete flow with confirmation

### Smart Navigation
- GUI menu for all warps
- Category filters
- Search support
- Pagination for large warp lists

### Teleport Experience
- Teleport countdown system
- Cancel on player movement
- Safe teleport checks for dangerous locations
- Sound and visual feedback

### Preview and Analytics
- Spectator-based warp preview mode
- Auto return after preview timeout
- Usage tracking per warp
- Last used and stats display

### Access and Data Tools
- Private warp access sharing
- MySQL-backed persistent storage
- Export and import support
- Automatic table creation and migration handling

## Command Reference

### Main Commands
- /setwarp <name> [category] [public|private]
- /warp <name>
- /warps
- /delwarp <name> [confirm]

### Warp Subcommands
- /warp preview <name>
- /warp stats <name>
- /warp top
- /warp edit
- /warp rename
- /warp access
- /warp export
- /warp import

## Warp Categories

- SPAWN
- SHOPS
- BASES
- EVENTS
- PLAYER_WARPS

## Requirements

| Item | Required |
|------|----------|
| Server Software | Paper 1.21.1+ |
| Java Runtime | Java 21+ |
| Database | MySQL 5.7+ or 8.0+ |

## Installation

1. Build the plugin jar or use prebuilt release.
2. Put jar file inside server plugins folder.
3. Start server once to generate config.
4. Update MySQL and owner UUID values in config.
5. Restart server.

## Build From Source

1. Open project folder.
2. Run gradle shadow jar build.
3. Output jar will be available in build/libs.

## Config Highlights

Main configuration areas:
- database: host, port, schema, credentials, pool size
- server-owners: UUID list for full delete authority
- teleport: countdown and safety check
- preview: preview duration seconds
- particles: warp marker visual settings

## Project Structure

- src/main/java/com/pixelwarp: all core source code
- src/main/resources: plugin.yml and config.yml defaults
- GUIDE.md: full usage and deep documentation

## Perfect For

- Public SMP servers
- Community servers with many player warps
- Servers needing category-based warp organization
- Admin teams that want database-backed reliability

## Future Friendly

PixelWarp is structured for easy extension, making it suitable for adding future gameplay systems, economy integration, and permission expansions.

## Support

If you are using this plugin in production, keep regular database backups and test config changes on a staging server first.

---

Built for modern Paper SMP servers.
