---
name: navigate-to
description: Start turn-by-turn navigation to an address or place.
---

# Navigate to

## Instructions

Call the `run_intent` tool with the following exact parameters:

- intent: navigate_to
- parameters: A JSON string with the following fields:
  - destination: the address or place to navigate to. String.

This opens Google Maps in navigation mode with the destination pre-filled — the user still
confirms the route in Maps before navigation actually starts. Use this (not `show-location-on-map`)
whenever the user wants directions or to start driving/walking somewhere, e.g. "navigate to the
next delivery address" or "take me to 123 Main St."
