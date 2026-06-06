---
document_id: it-002
title: Server Thermal Shutdown
error_codes: [THERM-TRIP]
tools_required: [esd strap, thermal paste]
---

## Symptom
The server shuts down under load and the BMC logs a thermal trip. CPU temperature crossed the critical threshold and firmware forced a shutdown to protect the hardware.

## Diagnosis
Verify fan operation and airflow path. Failed fans, dust buildup, or dried thermal paste raise CPU temperature beyond limits.

## Fix
Replace failed fans, clear dust from intakes and heatsinks, and reapply thermal paste to the CPU. Confirm temperatures stay nominal under a stress test.
