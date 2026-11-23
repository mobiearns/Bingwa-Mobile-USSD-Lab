---
name: USSD input safety
description: Safety and reliability rules for automated USSD value entry.
---

For automated USSD responses, write once with `ACTION_SET_TEXT` to the input field selected from the currently validated popup. Do not use clipboard, key/gesture typing, alternate fields, or soft-trust an unreadable value. Associate write verification with the current step and submit only after exact read-back.

**Why:** USSD replies can authorize purchases or account actions; competing write strategies and stale verification can submit a value on the wrong step.

**How to apply:** When changing popup input or send flows, preserve strict popup scoping, step-bound verification, and fail-closed behavior when the entered value cannot be confirmed.
