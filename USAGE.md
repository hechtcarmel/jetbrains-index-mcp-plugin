# IDE Index MCP Server - Tool Reference

This document provides detailed documentation for all MCP tools and resources available in the IDE Index MCP Server plugin.

## Table of Contents

- [Common Parameters](#common-parameters)
- [Navigation Tools](#navigation-tools)
- [Code Intelligence Tools](#code-intelligence-tools)
- [Project Structure Tools](#project-structure-tools)
- [Refactoring Tools](#refactoring-tools)
- [Resources](#resources)
- [Error Handling](#error-handling)

---

## Common Parameters

All tools accept an optional `project_path` parameter:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project_path` | string | No | Absolute path to the project root. Required when multiple projects are open in the IDE. |

### Position Parameters

Most tools operate on a specific location in code and require these parameters:

| Parameter | Type | Description |
|-----------|------|-------------|
| `file` | string | Path to the file relative to project root (e.g., `src/main/java/MyClass.java`) |
| `line` | integer | 1-based line number |
| `column` | integer | 1-based column number |

---

## Navigation Tools

### ide_find_references

Finds all references to a symbol across the entire project using IntelliJ's semantic index.

**Use when:**
- Locating where a method, class, variable, or field is called or accessed
- Understanding code dependencies
- Preparing for refactoring

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Yes | Path to the file relative to project root |
| `line` | integer | Yes | 1-based line number |
| `column` | integer | Yes | 1-based column number |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_find_references",
    "arguments": {
      "file": "src/main/java/com/example/UserService.java",
      "line": 15,
      "column": 20
    }
  }
}
```

**Example Response:**

```json
{
  "usages": [
    {
      "file": "src/main/java/com/example/UserController.java",
      "line": 42,
      "column": 15,
      "context": "userService.findUser(id)",
      "type": "METHOD_CALL"
    },
    {
      "file": "src/test/java/com/example/UserServiceTest.java",
      "line": 28,
      "column": 10,
      "context": "service.findUser(\"test\")",
      "type": "METHOD_CALL"
    }
  ],
  "totalCount": 2
}
```

**Reference Types:**
- `METHOD_CALL` - Method invocation
- `FIELD_ACCESS` - Field read/write
- `REFERENCE` - General reference
- `IMPORT` - Import statement
- `PARAMETER` - Method parameter
- `VARIABLE` - Variable usage

---

### ide_find_definition

Finds the definition/declaration location of a symbol at a given source location.

**Use when:**
- Understanding where a method, class, variable, or field is declared
- Looking up the original definition from a usage site

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Yes | Path to the file relative to project root |
| `line` | integer | Yes | 1-based line number |
| `column` | integer | Yes | 1-based column number |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_find_definition",
    "arguments": {
      "file": "src/main/java/com/example/App.java",
      "line": 25,
      "column": 12
    }
  }
}
```

**Example Response:**

```json
{
  "file": "src/main/java/com/example/UserService.java",
  "line": 15,
  "column": 17,
  "preview": "14:     \n15:     public User findUser(String id) {\n16:         return userRepository.findById(id);\n17:     }",
  "symbolName": "findUser"
}
```

---

### ide_type_hierarchy

Retrieves the complete type hierarchy for a class or interface.

**Use when:**
- Exploring class inheritance chains
- Understanding polymorphism
- Finding all subclasses or implementations
- Analyzing interface implementations

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | No* | Path to the file relative to project root |
| `line` | integer | No* | 1-based line number |
| `column` | integer | No* | 1-based column number |
| `className` | string | No* | Fully qualified class name (alternative to position) |

*Either `file`/`line`/`column` OR `className` must be provided.

**Example Request (by position):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_type_hierarchy",
    "arguments": {
      "file": "src/main/java/com/example/ArrayList.java",
      "line": 5,
      "column": 14
    }
  }
}
```

**Example Request (by class name):**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_type_hierarchy",
    "arguments": {
      "className": "java.util.ArrayList"
    }
  }
}
```

**Example Response:**

```json
{
  "element": {
    "name": "com.example.UserServiceImpl",
    "file": "src/main/java/com/example/UserServiceImpl.java",
    "kind": "CLASS"
  },
  "supertypes": [
    {
      "name": "com.example.UserService",
      "file": "src/main/java/com/example/UserService.java",
      "kind": "INTERFACE"
    },
    {
      "name": "com.example.BaseService",
      "file": "src/main/java/com/example/BaseService.java",
      "kind": "ABSTRACT_CLASS"
    }
  ],
  "subtypes": [
    {
      "name": "com.example.AdminUserServiceImpl",
      "file": "src/main/java/com/example/AdminUserServiceImpl.java",
      "kind": "CLASS"
    }
  ]
}
```

**Kind Values:**
- `CLASS` - Concrete class
- `ABSTRACT_CLASS` - Abstract class
- `INTERFACE` - Interface
- `ENUM` - Enum type
- `ANNOTATION` - Annotation type
- `RECORD` - Record class (Java 16+)

---

### ide_call_hierarchy

Analyzes method call relationships to find callers or callees.

**Use when:**
- Tracing execution flow
- Understanding code dependencies
- Analyzing impact of method changes
- Debugging to understand how a method is reached

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Yes | Path to the file relative to project root |
| `line` | integer | Yes | 1-based line number |
| `column` | integer | Yes | 1-based column number |
| `direction` | string | Yes | `"callers"` or `"callees"` |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_call_hierarchy",
    "arguments": {
      "file": "src/main/java/com/example/UserService.java",
      "line": 20,
      "column": 10,
      "direction": "callers"
    }
  }
}
```

**Example Response:**

```json
{
  "element": {
    "name": "UserService.validateUser(String)",
    "file": "src/main/java/com/example/UserService.java",
    "line": 20
  },
  "calls": [
    {
      "name": "UserController.createUser(UserRequest)",
      "file": "src/main/java/com/example/UserController.java",
      "line": 45
    },
    {
      "name": "UserController.updateUser(String, UserRequest)",
      "file": "src/main/java/com/example/UserController.java",
      "line": 62
    }
  ]
}
```

---

### ide_find_implementations

Finds all concrete implementations of an interface, abstract class, or abstract method.

**Use when:**
- Locating classes that implement an interface
- Finding classes that extend an abstract class
- Finding all overriding methods for polymorphic behavior analysis

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Yes | Path to the file relative to project root |
| `line` | integer | Yes | 1-based line number |
| `column` | integer | Yes | 1-based column number |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_find_implementations",
    "arguments": {
      "file": "src/main/java/com/example/Repository.java",
      "line": 8,
      "column": 10
    }
  }
}
```

**Example Response:**

```json
{
  "implementations": [
    {
      "name": "com.example.JpaUserRepository",
      "file": "src/main/java/com/example/JpaUserRepository.java",
      "line": 12,
      "kind": "CLASS"
    },
    {
      "name": "com.example.InMemoryUserRepository",
      "file": "src/main/java/com/example/InMemoryUserRepository.java",
      "line": 8,
      "kind": "CLASS"
    }
  ],
  "totalCount": 2
}
```

---

## Code Intelligence Tools

### ide_get_symbol_info

Gets detailed information about a symbol including type, documentation, and modifiers.

**Use when:**
- Understanding what a symbol represents
- Getting documentation for a method or class
- Checking modifiers and visibility

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Yes | Path to the file relative to project root |
| `line` | integer | Yes | 1-based line number |
| `column` | integer | Yes | 1-based column number |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_get_symbol_info",
    "arguments": {
      "file": "src/main/java/com/example/UserService.java",
      "line": 15,
      "column": 17
    }
  }
}
```

**Example Response:**

```json
{
  "name": "findUser",
  "kind": "METHOD",
  "type": "User",
  "containingClass": "com.example.UserService",
  "modifiers": ["public"],
  "parameters": [
    {
      "name": "id",
      "type": "String"
    }
  ],
  "documentation": "Finds a user by their unique identifier.\n\n@param id the user ID\n@return the User object, or null if not found"
}
```

---

### ide_get_completions

Gets code completions at a given position.

**Use when:**
- Suggesting what can be typed next
- Finding available methods on an object
- Discovering API options

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Yes | Path to the file relative to project root |
| `line` | integer | Yes | 1-based line number |
| `column` | integer | Yes | 1-based column number |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_get_completions",
    "arguments": {
      "file": "src/main/java/com/example/App.java",
      "line": 20,
      "column": 25
    }
  }
}
```

**Example Response:**

```json
{
  "completions": [
    {
      "text": "findUser",
      "displayText": "findUser(String id)",
      "type": "METHOD",
      "returnType": "User",
      "documentation": "Finds a user by ID"
    },
    {
      "text": "findAllUsers",
      "displayText": "findAllUsers()",
      "type": "METHOD",
      "returnType": "List<User>",
      "documentation": "Returns all users"
    }
  ]
}
```

---

### ide_get_inspections

Runs code inspections on a file or range to find problems.

**Use when:**
- Finding code issues
- Checking code quality
- Identifying potential bugs

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Yes | Path to the file relative to project root |
| `startLine` | integer | No | Start line for range inspection (1-based) |
| `endLine` | integer | No | End line for range inspection (1-based) |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_get_inspections",
    "arguments": {
      "file": "src/main/java/com/example/UserService.java"
    }
  }
}
```

**Example Response:**

```json
{
  "problems": [
    {
      "message": "Field 'logger' can be made final",
      "severity": "WARNING",
      "line": 8,
      "column": 12,
      "inspectionId": "FieldMayBeFinal"
    },
    {
      "message": "Unused import 'java.util.Date'",
      "severity": "WARNING",
      "line": 3,
      "column": 1,
      "inspectionId": "UnusedImport"
    }
  ],
  "totalCount": 2
}
```

**Severity Values:**
- `ERROR` - Compilation error
- `WARNING` - Potential problem
- `WEAK_WARNING` - Minor issue
- `INFO` - Informational

---

### ide_get_quick_fixes

Gets available quick fixes at a specific position.

**Use when:**
- Finding ways to fix a problem
- Getting IDE suggestions
- Automating code corrections

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Yes | Path to the file relative to project root |
| `line` | integer | Yes | 1-based line number |
| `column` | integer | Yes | 1-based column number |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_get_quick_fixes",
    "arguments": {
      "file": "src/main/java/com/example/App.java",
      "line": 15,
      "column": 10
    }
  }
}
```

**Example Response:**

```json
{
  "fixes": [
    {
      "fixId": "fix_001",
      "description": "Add 'final' modifier",
      "familyName": "Add modifier"
    },
    {
      "fixId": "fix_002",
      "description": "Remove unused variable",
      "familyName": "Remove unused element"
    }
  ]
}
```

---

### ide_apply_quick_fix

Applies a quick fix at a position.

**Use when:**
- Automatically fixing code issues
- Applying IDE suggestions

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Yes | Path to the file relative to project root |
| `line` | integer | Yes | 1-based line number |
| `column` | integer | Yes | 1-based column number |
| `fixId` | string | Yes | The fix ID from `ide_get_quick_fixes` |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_apply_quick_fix",
    "arguments": {
      "file": "src/main/java/com/example/App.java",
      "line": 15,
      "column": 10,
      "fixId": "fix_001"
    }
  }
}
```

**Example Response:**

```json
{
  "success": true,
  "message": "Applied fix: Add 'final' modifier",
  "affectedFiles": ["src/main/java/com/example/App.java"]
}
```

---

## Project Structure Tools

### ide_get_project_structure

Gets the project module structure with source roots.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| (none) | | | No parameters required |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_get_project_structure",
    "arguments": {}
  }
}
```

**Example Response:**

```json
{
  "projectName": "my-application",
  "basePath": "/Users/dev/my-application",
  "modules": [
    {
      "name": "app",
      "sourceRoots": [
        "src/main/java",
        "src/main/kotlin"
      ],
      "testSourceRoots": [
        "src/test/java",
        "src/test/kotlin"
      ],
      "resourceRoots": [
        "src/main/resources"
      ]
    }
  ]
}
```

---

### ide_get_file_structure

Gets the structure of a file (classes, methods, fields).

**Use when:**
- Understanding file organization
- Getting an overview of a class
- Navigating large files

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Yes | Path to the file relative to project root |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_get_file_structure",
    "arguments": {
      "file": "src/main/java/com/example/UserService.java"
    }
  }
}
```

**Example Response:**

```json
{
  "file": "src/main/java/com/example/UserService.java",
  "elements": [
    {
      "name": "UserService",
      "kind": "CLASS",
      "line": 8,
      "modifiers": ["public"],
      "children": [
        {
          "name": "repository",
          "kind": "FIELD",
          "line": 10,
          "type": "UserRepository",
          "modifiers": ["private", "final"]
        },
        {
          "name": "findUser",
          "kind": "METHOD",
          "line": 15,
          "returnType": "User",
          "parameters": ["String id"],
          "modifiers": ["public"]
        },
        {
          "name": "saveUser",
          "kind": "METHOD",
          "line": 22,
          "returnType": "void",
          "parameters": ["User user"],
          "modifiers": ["public"]
        }
      ]
    }
  ]
}
```

---

### ide_get_dependencies

Gets the project dependencies (libraries and versions).

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| (none) | | | No parameters required |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_get_dependencies",
    "arguments": {}
  }
}
```

**Example Response:**

```json
{
  "dependencies": [
    {
      "name": "org.springframework:spring-core",
      "version": "6.1.0",
      "scope": "COMPILE"
    },
    {
      "name": "junit:junit",
      "version": "4.13.2",
      "scope": "TEST"
    }
  ]
}
```

---

### ide_index_status

Checks if the IDE is in dumb mode (indexing) or smart mode.

**Use when:**
- Checking if index-dependent operations will work
- Waiting for indexing to complete

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| (none) | | | No parameters required |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_index_status",
    "arguments": {}
  }
}
```

**Example Response:**

```json
{
  "isDumbMode": false,
  "isSmartMode": true,
  "isIndexing": false,
  "projectName": "my-application"
}
```

---

## Refactoring Tools

> **Warning**: All refactoring tools modify source files. Changes can be undone with Ctrl/Cmd+Z.

### ide_refactor_rename

Renames a symbol and updates all references across the project.

**Use when:**
- Renaming identifiers to improve code clarity
- Following naming conventions
- Refactoring code structure

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Yes | Path to the file containing the symbol |
| `line` | integer | Yes | 1-based line number |
| `column` | integer | Yes | 1-based column number |
| `newName` | string | Yes | The new name for the symbol |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_refactor_rename",
    "arguments": {
      "file": "src/main/java/com/example/UserService.java",
      "line": 15,
      "column": 17,
      "newName": "findUserById"
    }
  }
}
```

**Example Response:**

```json
{
  "success": true,
  "affectedFiles": [
    "src/main/java/com/example/UserService.java",
    "src/main/java/com/example/UserController.java",
    "src/test/java/com/example/UserServiceTest.java"
  ],
  "changesCount": 8,
  "message": "Successfully renamed 'findUser' to 'findUserById'"
}
```

---

### ide_safe_delete

Safely deletes an element, first checking for usages.

**Use when:**
- Removing unused code
- Cleaning up dead code
- Safely removing methods or classes

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | Yes | Path to the file |
| `line` | integer | Yes | 1-based line number |
| `column` | integer | Yes | 1-based column number |

**Example Request:**

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_safe_delete",
    "arguments": {
      "file": "src/main/java/com/example/LegacyHelper.java",
      "line": 8,
      "column": 14
    }
  }
}
```

**Example Response (safe to delete):**

```json
{
  "success": true,
  "message": "Successfully deleted 'LegacyHelper'"
}
```

**Example Response (blocked by usages):**

```json
{
  "success": false,
  "message": "Cannot safely delete: 3 usages found",
  "blockingUsages": [
    {
      "file": "src/main/java/com/example/App.java",
      "line": 25,
      "context": "LegacyHelper.convert(data)"
    }
  ]
}
```

---

## Resources

MCP resources provide read-only access to project state.

### index://status

Returns the current IDE indexing status.

**Read Request:**

```json
{
  "method": "resources/read",
  "params": {
    "uri": "index://status"
  }
}
```

**Response:**

```json
{
  "isDumbMode": false,
  "isIndexing": false,
  "isSmartMode": true,
  "projectName": "my-application"
}
```

---

### project://structure

Returns the project module structure.

**Read Request:**

```json
{
  "method": "resources/read",
  "params": {
    "uri": "project://structure"
  }
}
```

---

### file://content/{path}

Returns the content of a file.

**Read Request:**

```json
{
  "method": "resources/read",
  "params": {
    "uri": "file://content/src/main/java/com/example/App.java"
  }
}
```

---

### symbol://info/{fqn}

Returns information about a symbol by fully qualified name.

**Read Request:**

```json
{
  "method": "resources/read",
  "params": {
    "uri": "symbol://info/com.example.UserService"
  }
}
```

---

## Error Handling

### JSON-RPC Standard Errors

| Code | Name | When It Occurs |
|------|------|----------------|
| -32700 | Parse Error | Invalid JSON in request |
| -32600 | Invalid Request | Missing required JSON-RPC fields |
| -32601 | Method Not Found | Unknown tool or method name |
| -32602 | Invalid Params | Missing or invalid parameters |
| -32603 | Internal Error | Unexpected server error |

### Custom MCP Errors

| Code | Name | When It Occurs |
|------|------|----------------|
| -32001 | Index Not Ready | IDE is indexing (dumb mode) |
| -32002 | File Not Found | Specified file doesn't exist |
| -32003 | Symbol Not Found | No symbol at the specified position |
| -32004 | Refactoring Conflict | Refactoring cannot be completed |

### Example Error Response

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": {
    "code": -32001,
    "message": "IDE is in dumb mode, indexes not available. Please wait for indexing to complete."
  }
}
```

### Handling Dumb Mode

Before calling index-dependent tools, you can check the index status:

```json
{
  "method": "tools/call",
  "params": {
    "name": "ide_index_status",
    "arguments": {}
  }
}
```

If `isDumbMode` is `true`, wait and retry later.
