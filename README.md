# CKSMPBlackMarket

Network-wide **black market auctions** for the Crescent Kingdom SMP, built on top of
[CKSMPCore](https://github.com/CrescentKingdom-Studio/CKSMPCore). On a schedule (or on demand) a run of secret
prizes goes up for auction one at a time; the highest bid when the timer ends wins. Everything is synced
across every shard of the network, so it does not matter which server a player is on, or whether they are
online when they win.

Inspired by AxDarkAuctions. The code is written from scratch for CKSMPCore's shared Postgres/Redis stack; no
code was taken from the open-source dark-auction plugins it was compared against.

> **Status:** compiles and packages cleanly. It has **not yet been run on a live server**, so treat it as a
> first drop to be tested on staging.

## Features

- Scheduled events on a fixed interval from midnight UTC, like CKSMPMobs (default: every 2 hours at :30), or fixed
  daily times, with an optional weekday filter, plus manual `/blackmarket start`.
- Same flow and feel as AxDarkAuctions: countdown announcements, each item is shown for a few seconds before
  bidding opens, a short bid window with anti-snipe extension, per-bid broadcasts, the same wording and colour
  palette, a 6-row menu whose bid button changes material with the amount (iron, gold, diamond, emerald,
  netherite) and shows your own head while you lead. Defaults mirror AxDarkAuctions' timings.
- Commands: `/blackmarket` and the `/bm` alias.
- Weighted prize pool (`items.yml`): CKSMPItems ids, vanilla materials and/or console commands.
- Bids are charged through Vault (CKSMPCore's economy); outbid players are refunded automatically.
- Winners get the prize wherever they are; offline winners receive it on next login. Full inventories overflow
  into CKSMPCore's `/stashbox`.
- Config-driven bidding menu (hosted by CKSMPCore's `GUIManager`), boss bar, chat, sounds. All text is
  MiniMessage in `Lang/messages.yml`, including `<center>` lines.
- PlaceholderAPI: `%blackmarket_status%`, `_item`, `_round`, `_rounds`, `_high_bid`, `_next_bid`, `_leader`,
  `_time_left`, `_next_event`.

## Requirements

Paper/Leaf 1.21.11, Java 25, **CKSMPCore**, **CKSMPItems**, Vault (provided by CKSMPCore). PlaceholderAPI is
optional.

## How it works across shards

| Concern | Mechanism |
|---|---|
| Source of truth | Tables `cksmpbm_*` in CKSMPCore's Postgres (shared Hikari pool). |
| Who wins a bid | One conditional `UPDATE ... RETURNING` (compare-and-set on round, leader and minimum bid). Two shards can never both lead. |
| Refunds and prizes | A payout ledger. `DELETE ... RETURNING` claims it atomically, so only the shard hosting the player pays out, and exactly once. Claimed on bid/win events and on `PlayerDataLoadEvent`. |
| Who runs the scheduler | A Postgres lease. One eligible shard holds it and renews it; if it dies, another takes over and resumes the event from the database. |
| Live updates | One Redis channel via CKSMPCore's `RedisManager.registerPackets`. Each change is published once and applied by every shard, publisher included. |
| Clock skew | Round end and lease expiry are decided with the database clock, never shard clocks. |

### What is reused from CKSMPCore

| Core system | Used for |
|---|---|
| `DatabaseManager` | Shared Postgres pool for all `cksmpbm_*` tables. |
| `RedisManager.registerPackets` / `sendPacket` | The single sync channel; also `hlen minecraft:smp:players` for the network player minimum. |
| Vault economy (`CurrencyManager`) | Bid charges, refunds and wallet checks. |
| `GUIManager` / `MenuInventoryHolder` | Hosts the bidding menu, so Core's click/drag/offhand protection applies. `ItemUtils.menuItem` builds the static buttons. |
| `CommandManagers` registry + CommandAPI | `/blackmarket` registration, same pattern as the other CKSMP plugins. |
| `ColourUtils` | MiniMessage rendering and `<center>` chat lines. |
| `StashManager` | Overflow when a winner's inventory is full. |
| `PlayerAPI.getProfile` / `PlayerDataLoadEvent` | Claim refunds and prizes once the wallet is loaded. |
| `registerExternalSaveHook` | A player's save or shard transfer waits for their in-flight bid to settle. |
| Native player settings | Per-player announcement opt-out (below). |
| `DiscordRelay.sendSystem` | Event starts and round winners in the Discord chat channel (leader shard only). |

**Announcement toggle.** Add the key to Core's `config.yml` and a `network-boolean` item to its settings menu, the
same way as the private-message / payment toggles:

```yaml
settings:
  player:
    blackmarket-announcements: true
```

Players without the key (or before you add it) receive broadcasts. The key is configurable via
`announcements.setting-key`.

Hot-path notes: the config is parsed once into immutable records (no YAML reads while bidding or rendering);
each shard keeps a single volatile round snapshot that the menu, boss bar and placeholders read lock-free; the
menu re-renders only the slots whose text changed; the scheduler tick is a few timestamp comparisons and only
touches the database on a state change or lease renewal.

## Installation

**Remove AxDarkAuctions first.** Both plugins register `/bm` (and `/blackmarket`), so running them side by
side makes the commands ambiguous.

1. Build with `./gradlew build` and drop `build/libs/CKSMPBlackMarket-1.0.0.jar` into `plugins/` on every shard.
   Config files are written on first start. **Keep `config.yml`, `items.yml`, `Lang/` and `Menus/` identical on every shard.**
2. On shards that must never drive events (e.g. a lobby) set `leader.eligible: false`.
3. Set `participate: false` on shards that should not show the menu / broadcasts.
4. The tables are created automatically (`CREATE TABLE IF NOT EXISTS`).

## Commands and permissions

| Command | Permission | Default |
|---|---|---|
| `/blackmarket` (`/bm`) opens the menu | `cksmpblackmarket.commands.open` | everyone |
| `/blackmarket status` | `cksmpblackmarket.commands.status` | everyone |
| `/blackmarket history` | `cksmpblackmarket.commands.history` | everyone |
| `/blackmarket start` | `cksmpblackmarket.commands.admin.start` | op |
| `/blackmarket stop` (cancels, refunds the leading bid) | `cksmpblackmarket.commands.admin.stop` | op |
| `/blackmarket skip` (ends the current round now) | `cksmpblackmarket.commands.admin.skip` | op |
| `/blackmarket reload` | `cksmpblackmarket.commands.admin.reload` | op |

`start`, `stop` and `skip` can be run from any shard; the request is relayed to whichever shard holds the lease.

## Managing prizes in-game

`/blackmarket editor` (`cksmpblackmarket.commands.admin.editor`) opens the prize pool as a chest. No typing, no
dialogs.

- **Add a prize:** drop any item onto an empty slot or the hopper, or shift-click one from your inventory. The
  item is saved as an exact copy of the stack, so AdvancedEnchantments enchants, other custom enchants, NBT,
  custom names and lore all work as they are. You keep your own item.
- **Edit a prize:** left-click it. Weight, starting bid, minimum raise and raise percentage are adjusted with
  clicks (left adds, right subtracts, shift multiplies; step sizes are in `config.yml` under `editor`).
  Toggle it on or off, give yourself a copy to check it, or delete it (click twice).
- **Multi-item prizes:** open "Prize items" in the prize menu and drop more items into that chest; whatever is in
  it when you close it or click save becomes the prize.
- **Quick toggle:** right-click a prize in the list.

Prizes are stored in Postgres (`cksmpbm_prize`) and an edit reaches every shard immediately. `items.yml` is only
a one-time seed for an empty table. Console-command prizes (for example crate keys) can be seeded from
`items.yml` but are not editable in the chest GUI yet.

Bid maths per prize: the first bid is `start-bid`, then `current + max(min-increment, current * increment-percent)`.

## Known limitations

- A bid is "wallet charged, then database settled". If a shard crashes in the instant between the two, the
  charge is not returned automatically. Money and prizes already in the ledger are safe.
- Payouts are deleted from the ledger as they are claimed; a crash between the claim and the delivery on the
  player's shard can lose that delivery. Failures the plugin can see (player left, reward removed from config)
  are restored to the ledger.
- Shard clocks only affect what the countdown displays; they should still be NTP-synced.
- Config files are per shard and not synced; keep them identical. The prize pool is the exception: it is in the database.

## Building

```
./gradlew build
```

`build` also runs the unit tests (bid maths, slot scheduling, weighted draw, cache ordering). Anything touching
Postgres, Redis or a live server is untested.

Needs `CKSMPCore` and `CKSMPItems` in your local Maven repository (`./gradlew publishToMavenLocal` in each).

## License

MIT, see [LICENSE](LICENSE).
