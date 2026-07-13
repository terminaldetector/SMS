---
name: create-contact
description: Create a new contact in the phone's contact list.
---

# Create contact

## Instructions

Call the `run_intent` tool with the following exact parameters:

- intent: create_contact
- parameters: A JSON string with the following fields:
  - first_name: the contact's first name. String.
  - last_name: the contact's last name. String.
  - phone_number: the contact's phone number. String.
  - email: the contact's email address. String.

This opens the Contacts app with a new contact pre-filled — the user still has to tap Save there
to actually create it.
