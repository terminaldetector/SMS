---
name: show-location-on-map
description: Show a location, address, or place on the map.
---

# Show location on map

## Instructions

Call the `run_intent` tool with the following exact parameters:

- intent: show_location_on_map
- parameters: A JSON string with the following fields:
  - location: the place, business name, or address to show. String.

Use this when the user wants to SEE a place on the map. If the user instead wants turn-by-turn
directions to get there, use the `navigate-to` skill instead.
