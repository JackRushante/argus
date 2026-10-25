# Security policy

## Reporting a vulnerability

Please report security problems privately, not in a public issue.

1. Open the **Security** tab of the repository on GitHub:
   <https://github.com/JackRushante/argus/security>.
2. Choose **Report a vulnerability**. This creates a private report visible only to the maintainer.

Include the Argus version, the Android version and device, the exact steps (the rule as shown on
the approval screen, if one is involved) and what you expected versus what happened. Do not attach
API keys, bearer tokens or personal data; redact them first.

## Supported versions

Only the latest release is supported. Fixes land in the next release; please check that the problem
still reproduces on the latest version before reporting.

## Scope

In scope is anything that breaks the guarantees described in the threat model in the
[Security & privacy](README.md#security--privacy) section of the README, for example:

- external content (SMS, notifications, web pages, model output) escaping its quoting in a shell
  command, changing the recipient of a reply, or modifying the automations themselves;
- a rule executing something other than what was shown and approved;
- leaking API keys, bearer tokens or message content from the device;
- bypassing the approval, whitelist or fail-closed checks.

Out of scope: problems that require a rooted or already-compromised device, social engineering of
the user into approving a rule they have read, and issues in third-party services or in a
self-hosted bridge you configured yourself.

## Disclosure and bounty

Reports are handled on a best-effort basis by a single maintainer. There is no bug bounty.
Please allow a reasonable time for a fix before any public disclosure.
