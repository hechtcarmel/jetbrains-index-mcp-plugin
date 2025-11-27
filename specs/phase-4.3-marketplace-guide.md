# Phase 4.3: Marketplace Preparation - Implementation Guide

**Document Version**: 1.0
**Based on**: tasks.md v1.2, design.md v1.1

---

## Overview

Phase 4.3 consists of 6 tasks (TASK-103 through TASK-108) focused on preparing the plugin for JetBrains Marketplace publication.

| Task | Description | Status |
|------|-------------|--------|
| TASK-103 | Create plugin icon | Not started |
| TASK-104 | Write marketplace description | Not started |
| TASK-105 | Update CHANGELOG.md | Partially done |
| TASK-106 | Configure plugin signing | Config exists |
| TASK-107 | Run plugin verifier | Config exists |
| TASK-108 | Prepare release workflow | Config exists |

---

## TASK-103: Create Plugin Icon (pluginIcon.svg)

### Purpose
The plugin icon appears in the IDE's plugin marketplace, settings, and tool windows.

### Requirements

| Requirement | Value |
|-------------|-------|
| Size | 40x40 pixels |
| Format | SVG (Scalable Vector Graphics) |
| Location | `src/main/resources/META-INF/pluginIcon.svg` |
| Dark variant | `src/main/resources/META-INF/pluginIcon_dark.svg` (optional) |

### JetBrains Guidelines

1. Use simple, recognizable shapes
2. Avoid fine details that won't scale well
3. Use the JetBrains color palette or complementary colors
4. The icon should represent the plugin's functionality

### Design Concepts

The icon should represent:
- **Connection to AI** - neural network, brain, or connection nodes
- **IDE integration** - code brackets, IDE symbol
- **MCP protocol** - protocol/API symbol, server icon

### Implementation Steps

#### Step 1: Create the main icon

Create file: `src/main/resources/META-INF/pluginIcon.svg`

```xml
<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40" viewBox="0 0 40 40">
  <!-- Background circle -->
  <circle cx="20" cy="20" r="18" fill="#3574F0"/>

  <!-- MCP/Index symbol - stylized brackets with connection -->
  <path d="M12 14 L8 20 L12 26" stroke="white" stroke-width="2.5" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M28 14 L32 20 L28 26" stroke="white" stroke-width="2.5" fill="none" stroke-linecap="round" stroke-linejoin="round"/>

  <!-- Connection dots representing MCP -->
  <circle cx="16" cy="20" r="2" fill="white"/>
  <circle cx="20" cy="20" r="2" fill="white"/>
  <circle cx="24" cy="20" r="2" fill="white"/>

  <!-- Connection lines -->
  <line x1="16" y1="20" x2="24" y2="20" stroke="white" stroke-width="1.5" opacity="0.6"/>
</svg>
```

#### Step 2: Create dark theme variant (optional but recommended)

Create file: `src/main/resources/META-INF/pluginIcon_dark.svg`

```xml
<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40" viewBox="0 0 40 40">
  <!-- Background circle - lighter for dark theme -->
  <circle cx="20" cy="20" r="18" fill="#4D8CF0"/>

  <!-- Same design, potentially with adjusted colors for visibility -->
  <path d="M12 14 L8 20 L12 26" stroke="white" stroke-width="2.5" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M28 14 L32 20 L28 26" stroke="white" stroke-width="2.5" fill="none" stroke-linecap="round" stroke-linejoin="round"/>

  <circle cx="16" cy="20" r="2" fill="white"/>
  <circle cx="20" cy="20" r="2" fill="white"/>
  <circle cx="24" cy="20" r="2" fill="white"/>

  <line x1="16" y1="20" x2="24" y2="20" stroke="white" stroke-width="1.5" opacity="0.6"/>
</svg>
```

#### Step 3: Verify

The icon is auto-discovered from META-INF - no explicit registration in plugin.xml is needed.

Test by running: `./gradlew runIde`

---

## TASK-104: Write Plugin Description for Marketplace

### Purpose
The marketplace description is what users see when browsing plugins. It needs to be compelling and informative.

### Location
Update `src/main/resources/META-INF/plugin.xml` - add `<description>` tag after `<vendor>`.

### Implementation Steps

#### Step 1: Add description to plugin.xml

Add the following after the `<vendor>` tag in `plugin.xml`:

```xml
<description><![CDATA[
<h2>IntelliJ Index MCP Server</h2>

<p>Expose your IDE's powerful indexing and refactoring capabilities to AI coding assistants via the
<a href="https://modelcontextprotocol.io">Model Context Protocol (MCP)</a>.</p>

<h3>🚀 Features</h3>

<h4>Navigation Tools</h4>
<ul>
    <li><b>Find References</b> - Locate all usages of any symbol across your project</li>
    <li><b>Go to Definition</b> - Navigate to symbol declarations</li>
    <li><b>Type Hierarchy</b> - Explore class and interface inheritance</li>
    <li><b>Call Hierarchy</b> - Trace method call chains</li>
    <li><b>Find Implementations</b> - Discover interface and abstract class implementations</li>
</ul>

<h4>Refactoring Tools</h4>
<ul>
    <li><b>Rename</b> - Safely rename symbols across the entire project</li>
    <li><b>Extract Method</b> - Extract code blocks into new methods</li>
    <li><b>Extract Variable</b> - Extract expressions into variables</li>
    <li><b>Inline</b> - Inline variables and methods</li>
    <li><b>Safe Delete</b> - Remove unused code safely</li>
    <li><b>Move</b> - Move elements between files</li>
</ul>

<h4>Code Intelligence</h4>
<ul>
    <li><b>Code Completions</b> - Get intelligent completion suggestions</li>
    <li><b>Code Analysis</b> - Run inspections and detect issues</li>
    <li><b>Quick Fixes</b> - List and apply available fixes</li>
    <li><b>Symbol Info</b> - Get documentation and type information</li>
</ul>

<h4>Project Insights</h4>
<ul>
    <li><b>Project Structure</b> - Query module and source root layout</li>
    <li><b>File Structure</b> - Get class, method, and field outlines</li>
    <li><b>Dependencies</b> - List project libraries and versions</li>
    <li><b>Index Status</b> - Check if IDE indexing is complete</li>
</ul>

<h3>🤖 Supported AI Assistants</h3>
<ul>
    <li>Claude Desktop</li>
    <li>Claude Code (CLI)</li>
    <li>Cursor</li>
    <li>VS Code with MCP extension</li>
    <li>Windsurf</li>
    <li>Any MCP-compatible client</li>
</ul>

<h3>⚡ Quick Start</h3>
<ol>
    <li>Install the plugin and restart the IDE</li>
    <li>Open the <b>Index MCP Server</b> tool window (View → Tool Windows)</li>
    <li>Click <b>Copy Config</b> and select your AI assistant</li>
    <li>Paste the configuration into your assistant's MCP settings</li>
    <li>Start using IDE-powered tools in your AI assistant!</li>
</ol>

<h3>🔧 How It Works</h3>
<p>The plugin runs an MCP server on your IDE's built-in web server, exposing tools that leverage
IntelliJ's semantic understanding of your code. AI assistants connect via HTTP+SSE transport and
can invoke any of the 20 available tools to perform accurate refactoring, navigation, and code analysis.</p>

<h3>📚 Documentation</h3>
<p>See the <a href="https://github.com/hechtcarmel/jetbrains-index-mcp-plugin">GitHub repository</a>
for detailed documentation, examples, and troubleshooting guides.</p>

<h3>🔒 Privacy</h3>
<p>All processing happens locally within your IDE. No code is sent to external servers by this plugin.</p>
]]></description>
```

#### Step 2: Prepare screenshots

Create screenshots for the marketplace listing showing:

1. **Tool window** - Server status panel with URL
2. **Command history** - List of executed commands with status
3. **Configuration dialog** - Settings panel
4. **AI assistant integration** - Example of Claude/Cursor using the tools

Screenshot requirements:
- Format: PNG
- Size: 1280x800 recommended
- Clear, readable text
- Light and dark theme variants if possible

Screenshots are uploaded separately to the marketplace, not included in the plugin.

---

## TASK-105: Update CHANGELOG.md

### Purpose
Document all features and version history for users and marketplace.

### Location
`CHANGELOG.md` in project root

### Implementation Steps

#### Step 1: Update with release version

Replace the current CHANGELOG.md content with:

```markdown
<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IntelliJ Index MCP Plugin Changelog

## [1.0.0] - 2025-XX-XX

### Added

#### MCP Server Infrastructure
- HTTP+SSE transport on IDE's built-in web server
  - SSE endpoint: `GET /index-mcp/sse`
  - JSON-RPC endpoint: `POST /index-mcp`
- JSON-RPC 2.0 protocol implementation
- Multi-project support with automatic project resolution
- `project_path` parameter for explicit project targeting

#### Navigation Tools (5 tools)
- `ide_find_references` - Find all usages of a symbol across the project
- `ide_find_definition` - Navigate to symbol definition location
- `ide_type_hierarchy` - Get class/interface type hierarchy
- `ide_call_hierarchy` - Get method caller/callee hierarchy
- `ide_find_implementations` - Find interface/abstract implementations

#### Refactoring Tools (6 tools)
- `ide_refactor_rename` - Rename symbols with reference updates
- `ide_refactor_extract_method` - Extract code to new method
- `ide_refactor_extract_variable` - Extract expression to variable
- `ide_refactor_inline` - Inline variable or method
- `ide_refactor_safe_delete` - Safely delete unused elements
- `ide_refactor_move` - Move elements to different files

#### Code Intelligence Tools (5 tools)
- `ide_code_completions` - Get code completion suggestions
- `ide_analyze_code` - Run code inspections on file/range
- `ide_list_quick_fixes` - Get available quick fixes at position
- `ide_apply_quick_fix` - Apply a specific quick fix
- `ide_inspect_symbol` - Get symbol information and documentation

#### Project Structure Tools (4 tools)
- `ide_project_structure` - Get project module tree
- `ide_file_structure` - Get file's class/method/field structure
- `ide_list_dependencies` - Get project library dependencies
- `ide_index_status` - Check IDE indexing status (dumb/smart mode)

#### MCP Resources (4 resources)
- `project://structure` - Project structure as JSON
- `file://content/{path}` - File content with metadata
- `symbol://info/{fqn}` - Symbol information by fully qualified name
- `index://status` - IDE indexing status

#### User Interface
- Tool window with server status and URL display
- Command history panel with chronological listing
- Status indicators (success=green, error=red, pending=yellow)
- Filtering by tool name and status
- Search within command history
- JSON viewer for request/response details
- Export history to JSON/CSV formats
- Clear history functionality

#### Client Configuration Generator
- One-click configuration for Claude Code CLI
- Copy-to-clipboard configs for:
  - Claude Desktop
  - Cursor
  - VS Code
  - Windsurf

#### Settings
- Maximum history size (default: 100)
- Auto-scroll toggle (default: enabled)
- Timestamp display toggle (default: enabled)
- Write operation confirmation toggle (default: enabled)

### Technical Details
- **Platform**: IntelliJ IDEA 2024.3+ (build 243+)
- **Language**: Kotlin 2.1+
- **Protocol**: MCP Specification 2024-11-05
- **Runtime**: JVM 21
- **Transport**: HTTP+SSE with JSON-RPC 2.0

---

## [Unreleased]

### Planned
- Additional language support improvements
- Performance optimizations for large projects
- Enhanced error messages and diagnostics
```

#### Step 2: Update version in gradle.properties

```properties
pluginVersion = 1.0.0
```

---

## TASK-106: Configure Plugin Signing

### Purpose
JetBrains requires plugins to be signed for marketplace distribution.

### Current Configuration

The signing configuration already exists in `build.gradle.kts` (lines 104-108):

```kotlin
signing {
    certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
    privateKey = providers.environmentVariable("PRIVATE_KEY")
    password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
}
```

### Implementation Steps

#### Option A: JetBrains Marketplace CA (Recommended for Public Distribution)

1. **Go to JetBrains Marketplace**
   - Navigate to: https://plugins.jetbrains.com/author/me/certificates
   - Sign in with your JetBrains account

2. **Generate Certificate**
   - Click "Generate Certificate"
   - Download the certificate chain file
   - Download the private key file

3. **Set Environment Variables**
   ```bash
   export CERTIFICATE_CHAIN="$(cat /path/to/chain.crt)"
   export PRIVATE_KEY="$(cat /path/to/private.pem)"
   export PRIVATE_KEY_PASSWORD="your_password_if_any"
   ```

#### Option B: Self-Signed Certificate (For Testing Only)

1. **Generate Private Key**
   ```bash
   openssl genpkey -algorithm RSA -out private.pem -pkeyopt rsa_keygen_bits:4096
   ```

2. **Generate Certificate**
   ```bash
   openssl req -new -x509 -key private.pem -out chain.crt -days 365 \
     -subj "/CN=Plugin Developer/O=Your Organization"
   ```

3. **Set Environment Variables**
   ```bash
   export CERTIFICATE_CHAIN="$(cat chain.crt)"
   export PRIVATE_KEY="$(cat private.pem)"
   export PRIVATE_KEY_PASSWORD=""
   ```

#### Step 3: Configure GitHub Secrets (for CI/CD)

Go to: Repository → Settings → Secrets and variables → Actions

Add the following secrets:

| Secret Name | Description |
|-------------|-------------|
| `CERTIFICATE_CHAIN` | Contents of the certificate chain file |
| `PRIVATE_KEY` | Contents of the private key file |
| `PRIVATE_KEY_PASSWORD` | Password for the private key (if any) |

#### Step 4: Test Signing Locally

```bash
./gradlew signPlugin
```

Check output in `build/distributions/` for signed ZIP file.

---

## TASK-107: Run Plugin Verifier

### Purpose
Ensure the plugin is compatible with target IDE versions and doesn't use deprecated APIs.

### Current Configuration

Already configured in `build.gradle.kts` (lines 118-122):

```kotlin
pluginVerification {
    ides {
        recommended()
    }
}
```

### Implementation Steps

#### Step 1: Run the Verifier

```bash
./gradlew runPluginVerifier
```

This may take several minutes as it downloads and tests against multiple IDE versions.

#### Step 2: Review Output

Output location: `build/reports/pluginVerifier/`

The verifier checks for:

| Check | Description |
|-------|-------------|
| API Compatibility | Methods/classes exist in target IDEs |
| Deprecated API | Usage of deprecated APIs |
| Internal API | Usage of internal/private APIs (should avoid) |
| Plugin Structure | Valid plugin.xml and resources |
| Binary Compatibility | Class file compatibility |

#### Step 3: Fix Common Issues

| Issue | Solution |
|-------|----------|
| `Deprecated API usage` | Replace with recommended alternative from deprecation message |
| `Internal API usage` | Find public API equivalent or request API exposure |
| `Missing dependency` | Add to plugin.xml dependencies |
| `Incompatible change` | Adjust code for API changes between versions |
| `Unresolved class` | Check bundledPlugins configuration |

#### Step 4: Configure Specific IDE Versions (Optional)

To test against specific versions, modify `build.gradle.kts`:

```kotlin
pluginVerification {
    ides {
        recommended()
        // Add specific versions:
        // ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3")
        // ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
    }
}
```

#### Step 5: Verify No Critical Issues

Before release, ensure:
- No "Compatibility Problems" with severity ERROR
- No internal API usage (warnings are acceptable but should be minimized)
- All target IDE versions pass verification

---

## TASK-108: Prepare Release Workflow

### Purpose
Automate the release process using GitHub Actions.

### Current Configuration

Two workflows already exist:

1. **`build.yml`** - Runs on push/PR
   - Builds plugin
   - Runs tests
   - Runs Qodana inspections
   - Runs plugin verifier
   - Creates draft release

2. **`release.yml`** - Runs on release publication
   - Signs plugin
   - Publishes to JetBrains Marketplace
   - Uploads release assets

### Implementation Steps

#### Step 1: Set Up GitHub Repository Secrets

Navigate to: Repository → Settings → Secrets and variables → Actions → New repository secret

| Secret | Description | How to Obtain |
|--------|-------------|---------------|
| `PUBLISH_TOKEN` | JetBrains Marketplace API token | https://plugins.jetbrains.com/author/me/tokens |
| `CERTIFICATE_CHAIN` | Signing certificate | See TASK-106 |
| `PRIVATE_KEY` | Signing private key | See TASK-106 |
| `PRIVATE_KEY_PASSWORD` | Key password | Your password or empty |
| `CODECOV_TOKEN` | Code coverage token (optional) | https://codecov.io |

#### Step 2: Get PUBLISH_TOKEN

1. Go to https://plugins.jetbrains.com/author/me/tokens
2. Click "Create new token"
3. Name: "GitHub Actions" or similar
4. Permissions: Select "Upload plugins"
5. Click "Create"
6. Copy the token immediately (it won't be shown again)
7. Add as GitHub secret `PUBLISH_TOKEN`

#### Step 3: Release Process

##### Pre-Release Checklist

```bash
# 1. Ensure clean working directory
git status

# 2. Update version in gradle.properties
# pluginVersion = 1.0.0

# 3. Update CHANGELOG.md with release date

# 4. Commit changes
git add gradle.properties CHANGELOG.md
git commit -m "Prepare release 1.0.0"
git push origin main

# 5. Wait for CI to pass and create draft release
```

##### Create Release

1. **Go to GitHub Releases**
   - Navigate to: Repository → Releases
   - Find the draft release created by CI

2. **Edit Draft Release**
   - Verify tag name (e.g., `v1.0.0` or `1.0.0`)
   - Review/edit release notes
   - Add any additional notes

3. **Publish Release**
   - Click "Publish release"
   - This triggers the `release.yml` workflow

4. **Monitor Workflow**
   - Go to Actions tab
   - Watch the "Release" workflow
   - Verify successful completion

##### Post-Release

1. **Verify Marketplace Publication**
   - Go to https://plugins.jetbrains.com/plugin/YOUR_PLUGIN_ID
   - Verify new version is available (may take a few minutes)

2. **Verify GitHub Release**
   - Check release page has the plugin ZIP attached

3. **Update Main Branch**
   - Merge the changelog PR created by the workflow

---

## Pre-Release Checklist

### Code Quality

```bash
# Build
./gradlew clean build

# Run all tests
./gradlew test

# Run plugin verifier
./gradlew runPluginVerifier

# Run Qodana (optional, runs in CI)
./gradlew qodana
```

### Documentation

- [ ] README.md is up to date
- [ ] USAGE.md documents all tools
- [ ] CHANGELOG.md has release version and date
- [ ] plugin.xml has complete description

### Assets

- [ ] pluginIcon.svg exists in META-INF
- [ ] pluginIcon_dark.svg exists (optional)
- [ ] Screenshots prepared for marketplace

### Configuration

- [ ] gradle.properties has correct version
- [ ] Signing certificates configured
- [ ] GitHub secrets configured

### Testing

```bash
# Manual test in IDE
./gradlew runIde

# Test signing
./gradlew signPlugin

# Final distribution build
./gradlew buildPlugin
# Check: build/distributions/*.zip
```

---

## Troubleshooting

### Signing Issues

**Error**: "Certificate chain is not valid"
- Ensure certificate is in PEM format
- Check certificate hasn't expired
- Verify environment variable is set correctly

**Error**: "Private key doesn't match certificate"
- Regenerate both certificate and key together
- Ensure you're using the matching pair

### Verifier Issues

**Error**: "Internal API usage"
- Search for public API alternatives
- Check IntelliJ Platform SDK documentation
- Consider filing issue for API exposure

**Error**: "Incompatible with IDE version X"
- Check sinceBuild in gradle.properties
- Review API changes in IDE release notes
- Add version-specific code paths if needed

### Marketplace Issues

**Error**: "Plugin signing verification failed"
- Use JetBrains Marketplace CA certificate
- Ensure all secrets are correctly configured
- Test signing locally first

**Error**: "Plugin ID already exists"
- Plugin ID in plugin.xml must be unique
- Contact JetBrains if legitimate ownership dispute

---

## Document History

| Version | Date | Description |
|---------|------|-------------|
| 1.0 | 2025-11-27 | Initial guide for Phase 4.3 |
