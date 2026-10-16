# Security Policy

Report potentially sensitive security issues privately to the repository maintainers once a public GitHub repository has been created. Do not submit resident photos or sensitive personal information in public issues.

The camera SDK contains no network client, no Supabase credentials, and no automatic gallery publication. FileProvider is not exported and uses `${applicationId}`-scoped authorities. Only confirmed capture files are exposed under a private cache path. Hosts must import returned files into their own secure storage and release stale URI results after ingestion.

Known limitation: the prototype has no physical-device security audit; do not process production resident identification photos until host integration and device tests are complete.
