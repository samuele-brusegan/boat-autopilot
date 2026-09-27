# Boat Autopilot

Client-side Forge mod for Minecraft 26.3. It follows water routes derived from
JourneyMap's client map images and does not require a server-side installation.

## Requirements

- Minecraft 26.3
- Forge 66.0.5
- JourneyMap for Forge 26.3 (recommended: 6.0.9)
- Java 25

## Use

- `/boatpilot waypoint <name>` starts a route to a JourneyMap waypoint in the
  current dimension. Names containing spaces are supported.
- `/boatpilot go <x> <y> <z>` starts a route to block coordinates.
- Press JourneyMap's fullscreen-map key (normally **J**, configurable in Controls),
  then hold **Shift** and left-click to choose a point.
- Press **O** or run `/boatpilot stop` to stop.

The player must be aboard a vanilla boat, raft, or chest boat. The route planner uses
JourneyMap's Day map image to classify water, land, and uncertain pixels, then
searches connected water. It adds a clearance cost near shores, aiming to keep at
least 16 blocks away when the mapped water allows it. In narrow straits the same
cost favors the route with the most water on both sides. A destination on land
snaps to reachable water within 64 blocks. The classifier can be inaccurate on
custom themes, resource packs, terrain overlays, or unlabeled areas; uncertain
sections are reported as unknown, and routes over 4096 horizontal blocks in
straight-line distance are rejected. After a persistent collision (3 seconds) or
about 2.5 seconds without movement, the controller attempts a short reverse and
turn maneuver, then resumes the route. It stops after two unsuccessful recovery
attempts; manual steering always stops navigation.

## Development

```sh
./gradlew test
./gradlew build
```

JourneyMap is a compile-only API dependency. Install its Forge 26.3 jar in the
development client `run/mods` directory to exercise waypoint and map-click
integration in-game.
