# Architecture diagrams

| Topic | README asset | Editable source |
|---|---|---|
| Angular, Spring API, engine and storage | [System](images/system-architecture.svg) | [draw.io](images/system-architecture.drawio) |
| Isolated workers and ordered scenario steps | [Execution](images/scenario-execution.svg) | [draw.io](images/scenario-execution.drawio) |
| Full-run aggregates and bounded sample retention | [Measurement](images/measurement.svg) | [draw.io](images/measurement.drawio) |

The diagrams use the same white rectangles, black text and simple arrows as the TCP Load Balancer and Local Search Engine READMEs. Open the `.drawio` sources in diagrams.net to edit their native boxes, labels and edges, then export the matching SVG. No AI-generated raster illustrations are used.

These diagrams describe logical responsibilities. Statistics are in-process objects, not a separate service. The database checkpoint stores a complete bounded report snapshot, and the API retrieves live state or stored reports. The scenario picture shows one worker; the run launches the configured number of independent copies. The diagram's successful path does not imply later steps execute after a failure.
