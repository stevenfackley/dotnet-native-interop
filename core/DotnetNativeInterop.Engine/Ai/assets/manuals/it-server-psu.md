---
document_id: it-001
title: Server Power Supply Failure
error_codes: [PSU-FAIL, AMBER-LED]
tools_required: [esd strap, Phillips #2]
---

## Symptom
The server does not power on and the power-supply LED is amber rather than green. A redundant unit may have failed while the second carries the load.

## Diagnosis
Check the LED state and the BMC event log. Amber typically indicates an input or internal fault; reseat the unit and confirm the input feed.

## Fix
Replace the failed power supply with a matching model, wearing an ESD strap. Confirm both units report green and the BMC clears the alert.
