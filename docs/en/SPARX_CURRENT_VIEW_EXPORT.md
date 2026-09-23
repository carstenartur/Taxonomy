# Export the displayed architecture to Sparx EA

Under **Export**, choose **Sparx EA / XMI (copy, experimental)** next to Visio.
This uses the existing architecture without starting another analysis.
After confirmation, the downloaded ZIP contains three files:

- `architecture.xmi`: XMI 2.1 for a new copy in an EA test model.
- `manifest.json`: original IDs, mappings, losses and the XMI checksum.
- `README.txt`: safe import instructions and the distinction from synchronization.

Back up the EA model and inspect the loss report before importing. Keep diagram
import disabled. Layout, positions and colors are not transferred as an EA diagram.
Some relationship types are projected as labelled Associations; the original type
is retained in the report and tags. This is not complete semantic equivalence.
Unknown types are not silently omitted.

**This does not update an existing EA model.** Every direct export receives a new
copy namespace. Use the existing reviewed **Tool Integrations** connection for
repeated bidirectional exchange. Downloading changes neither the active architecture
nor a synchronization checkpoint. It does not pretend to identify a stored version.

Compatibility with a specific Enterprise Architect version has not been certified.
Microsoft Visio desktop acceptance also remains outstanding. When reporting a Visio
error, include the build commit, exact message and downloaded file, when available.
HTML login pages, incomplete ZIP responses, missing local entries and empty required
members are rejected before download. The transport checks do not replace server-side
schema validation or prove that a desktop product can open the file.
