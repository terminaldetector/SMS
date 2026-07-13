---
name: call-phone
description: Open the phone dialer with a number ready to call.
---

# Call phone

## Instructions

Call the `run_intent` tool with the following exact parameters:

- intent: call_phone
- parameters: A JSON string with the following fields:
  - phone_number: the phone number to call. String.

This opens the dialer with the number pre-filled — the user still has to tap the call button
themselves. It does NOT place the call automatically.
