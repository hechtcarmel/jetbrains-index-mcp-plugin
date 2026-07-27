# Security Policy

## Supported Versions

Only the latest release published on the
[JetBrains Marketplace](https://plugins.jetbrains.com/) is supported with
security fixes. Please update to the latest version before reporting.

## Reporting a Vulnerability

Please **do not** open a public issue for security problems. Instead, report
privately via GitHub's private vulnerability reporting:

https://github.com/hechtcarmel/jetbrains-index-mcp-plugin/security/advisories/new

You should receive an acknowledgement within a few days. Coordinated
disclosure is appreciated — please give the maintainer a chance to ship a fix
before publishing details.

## Scope Notes

- The plugin runs an embedded HTTP MCP server that binds to `127.0.0.1`
  (localhost) only. It is never exposed to the network by design.
- Loopback Origin/Host validation is in place to mitigate DNS-rebinding
  attacks against the local server.
- Reports that require the user to have deliberately reconfigured the server
  to a non-loopback bind are out of scope.
