---
document_id: it-003
title: Network Port Link Down
error_codes: [LINK-DOWN]
tools_required: [cable tester, optical cleaner]
---

## Symptom
A switch port shows link down and the attached server loses connectivity. The port LED is dark or flapping between up and down states.

## Diagnosis
Test the cable and check the transceiver. A bad patch cable, dirty fiber end-face, or unseated SFP causes an unstable or absent link.

## Fix
Replace the cable or clean the fiber end-face, reseat the transceiver, and confirm the port comes up at the negotiated speed without flapping.
